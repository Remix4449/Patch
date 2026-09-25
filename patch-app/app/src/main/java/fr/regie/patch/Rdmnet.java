package fr.regie.patch;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * RDMnet (E1.33) côté contrôleur : une connexion TCP à un broker, la liste
 * des appareils qui y sont connectés, et le transport des commandes RDM
 * (RPT Request / Notification) pour Rdm.
 *
 * Le broker est cherché en mDNS (Mdns). S'il n'y en a pas, l'application en
 * sert un (Broker) et s'y connecte elle-même, par la boucle locale : les
 * appareils RDMnet du plateau viennent alors s'y brancher.
 *
 * Toutes les longueurs de PDU sont sur trois octets (drapeaux 0xF0), comme le
 * demande E1.33 sur TCP.
 */
public class Rdmnet implements Rdm.Transport {

    /* Vecteurs E1.33. */
    static final int ROOT_RPT = 0x00000005, ROOT_BROKER = 0x00000009;
    static final int BROKER_CONNECT = 0x0001, BROKER_CONNECT_REPLY = 0x0002,
            BROKER_CLIENT_ENTRY_UPDATE = 0x0003, BROKER_FETCH_CLIENT_LIST = 0x0006,
            BROKER_CONNECTED_CLIENT_LIST = 0x0007, BROKER_CLIENT_ADD = 0x0008,
            BROKER_CLIENT_REMOVE = 0x0009, BROKER_CLIENT_ENTRY_CHANGE = 0x000A,
            BROKER_REQUEST_DYNAMIC_UIDS = 0x000B, BROKER_ASSIGNED_DYNAMIC_UIDS = 0x000C,
            BROKER_DISCONNECT = 0x000E, BROKER_NULL = 0x000F;
    static final int CLIENT_PROTOCOL_RPT = 0x00000005;
    static final int RPT_REQUEST = 0x00000001, RPT_STATUS = 0x00000002, RPT_NOTIFICATION = 0x00000003;
    static final int REQUEST_RDM_CMD = 0x00000001, NOTIFICATION_RDM_CMD = 0x00000001;
    static final int RPT_CLIENT_DEVICE = 0, RPT_CLIENT_CONTROLLER = 1;
    static final String PORTEE = "default";
    static final byte[] ACN = { 0x41, 0x53, 0x43, 0x2d, 0x45, 0x31, 0x2e, 0x31, 0x37, 0, 0, 0 };

    private static final int ATTENTE_MS = 3000;

    /** Un client RPT connu du broker. */
    public static class Client {
        public byte[] cid = new byte[16], uid = new byte[6];
        public int type;
        public String texte() { return Rdm.uidTexte(uid); }
    }

    public final Map<String, Client> clients = new ConcurrentHashMap<String, Client>();
    public volatile boolean connecte, actif;
    public volatile String broker = "", message = "", mode = "";
    public volatile String brokerUid = "";

    private final Rdm rdm;
    private final byte[] cid = octets(UUID.randomUUID());
    private final byte[] uid = new byte[6];
    private final Map<Long, LinkedBlockingQueue<Object>> attente = new ConcurrentHashMap<Long, LinkedBlockingQueue<Object>>();
    private long sequence = 1;
    private Socket sock;
    private OutputStream sortie;
    private volatile String hote = "";
    private volatile int port;
    private Thread boucle;

    public Rdmnet(Rdm r) {
        rdm = r;
        Random x = new Random();
        uid[0] = 0x7F; uid[1] = (byte) 0xF1;
        for (int i = 2; i < 6; i++) uid[i] = (byte) x.nextInt(256);
    }

    /* ------------------------------ pilote ------------------------------ */

    public Broker interne;
    private Mdns mdns;
    private volatile boolean cherche;

    /**
     * Cherche un broker en mDNS et s'y connecte ; à défaut, en sert un sur
     * le téléphone et s'y connecte par la boucle locale.
     */
    public void demarrerAuto(final String ipLocale) {
        synchronized (this) {
            if (cherche || actif) return;
            cherche = true;
        }
        rdm.rdmnet = this;
        mode = "recherche"; message = "Recherche d'un broker RDMnet…";
        new Thread(new Runnable() {
            public void run() {
                try {
                    if (ipLocale == null || ipLocale.isEmpty()) { message = "Pas de Wi-Fi : RDMnet attend le réseau"; mode = ""; return; }
                    List<Mdns.Trouve> l = new ArrayList<Mdns.Trouve>();
                    try {
                        if (mdns == null) { mdns = new Mdns(); mdns.demarrer(ipLocale); }
                        l = mdns.chercher(3000);
                    } catch (Exception e) { message = "mDNS indisponible"; }
                    if (!l.isEmpty()) connecter(l.get(0).ip, l.get(0).port, "externe");
                    else servir(ipLocale);
                } finally { cherche = false; }
            }
        }, "rdmnet-recherche").start();
    }

    /** Sert un broker sur le téléphone, l'annonce, et s'y connecte. */
    public void servir(String ipLocale) {
        if (interne == null) interne = new Broker();
        int p = interne.demarrer(8888);
        if (p < 0) { message = "Impossible d'ouvrir le broker"; mode = ""; return; }
        if (mdns == null) {
            try { mdns = new Mdns(); mdns.demarrer(ipLocale); } catch (Exception e) { mdns = null; }
        }
        if (mdns != null)
            mdns.annoncer("Patch " + Rdm.uidTexte(interne.uid).substring(5), ipLocale, p, interne.cid, interne.uid);
        connecter("127.0.0.1", p, "interne");
        broker = ipLocale + ":" + p;
    }

    /** Tout arrêter : connexion, broker interne, mDNS. */
    public void toutArreter() {
        arreter();
        if (interne != null) interne.arreter();
        if (mdns != null) mdns.arreter();
        mdns = null;
    }

    /* --------------------------- connexion ------------------------------ */

    /** Se connecte à ce broker et s'y tient : reconnexion tant qu'on n'a pas arrêté. */
    public synchronized void connecter(String h, int p, String m) {
        hote = h; port = p; mode = m;
        broker = h + ":" + p;
        if (!"interne".equals(m) && interne != null) interne.arreter();
        rdm.rdmnet = this;
        if (actif) { fermer(); return; }        // la boucle en cours repart sur la nouvelle adresse
        actif = true;
        boucle = new Thread(new Runnable() { public void run() { tourner(); } }, "rdmnet");
        boucle.setDaemon(true);
        boucle.start();
    }

    public synchronized void arreter() {
        actif = false;
        fermer();
        if (rdm.rdmnet == this) rdm.rdmnet = null;
        mode = ""; message = "";
    }

    private void fermer() {
        try { if (sock != null) sock.close(); } catch (Exception ignore) { }
    }

    private void tourner() {
        while (actif) {
            try {
                message = "Connexion à " + broker + "…";
                Socket s = new Socket();
                s.setTcpNoDelay(true);
                s.connect(new InetSocketAddress(hote, port), 4000);
                s.setSoTimeout(60000);
                sock = s;
                sortie = s.getOutputStream();
                envoyer(ROOT_BROKER, broker(BROKER_CONNECT, connexion()));
                lancerCoeur(s);
                DataInputStream in = new DataInputStream(s.getInputStream());
                while (actif && !s.isClosed()) {
                    byte[] bloc = lireBloc(in);
                    for (int[] p : pdus(bloc, 0, bloc.length)) recu(bloc, p);
                }
            } catch (Exception e) {
                if (actif) message = "Broker injoignable (" + broker + ")";
            }
            connecte = false;
            clients.clear();
            rdm.oublierRdmnet();
            for (LinkedBlockingQueue<Object> q : attente.values()) q.offer(Rdm.Reponse.echec("connexion perdue"));
            fermer();
            if (!actif) break;
            try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
        }
    }

    /** Un message au moins toutes les quinze secondes, sinon le broker nous lâche. */
    private void lancerCoeur(final Socket s) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                while (actif && !s.isClosed()) {
                    try { Thread.sleep(10000); } catch (InterruptedException e) { return; }
                    try { if (sock == s) envoyer(ROOT_BROKER, broker(BROKER_NULL, new byte[0])); }
                    catch (Exception e) { return; }
                }
            }
        }, "rdmnet-coeur");
        t.setDaemon(true);
        t.start();
    }

    /** Client Connect : portée, version, domaine, puis notre fiche de contrôleur RPT. */
    private byte[] connexion() {
        ByteBuffer b = ByteBuffer.allocate(63 + 2 + 231 + 1 + 46);
        b.put(Arrays.copyOf(PORTEE.getBytes(StandardCharsets.UTF_8), 63));
        b.putShort((short) 1);
        b.put(Arrays.copyOf("local.".getBytes(StandardCharsets.UTF_8), 231));
        b.put((byte) 0x01);                             // mises à jour incrémentales
        b.put(ficheClient(cid, uid, RPT_CLIENT_CONTROLLER));
        return b.array();
    }

    static byte[] ficheClient(byte[] cid, byte[] uid, int type) {
        ByteBuffer b = ByteBuffer.allocate(46);
        longueur(b, 46);
        b.putInt(CLIENT_PROTOCOL_RPT);
        b.put(cid).put(uid).put((byte) type);
        b.put(new byte[16]);                            // binding CID : aucun
        return b.array();
    }

    /* ----------------------------- réception ---------------------------- */

    private void recu(byte[] b, int[] racine) {
        int vec = ByteBuffer.wrap(b, racine[1], 4).getInt();
        int o = racine[1] + 4 + 16;
        if (vec == ROOT_BROKER) {
            for (int[] p : pdus(b, o, racine[2])) brokerRecu(b, p);
        } else if (vec == ROOT_RPT) {
            for (int[] p : pdus(b, o, racine[2])) rptRecu(b, p);
        }
    }

    private void brokerRecu(byte[] b, int[] p) {
        int vec = u16(b, p[1]);
        int d = p[1] + 2;
        switch (vec) {
            case BROKER_CONNECT_REPLY: {
                int code = u16(b, d);
                if (code != 0) {
                    message = "Refusé par le broker : " + refus(code);
                    fermer();
                    return;
                }
                brokerUid = Rdm.uidTexte(Arrays.copyOfRange(b, d + 4, d + 10));
                System.arraycopy(b, d + 10, uid, 0, 6);
                connecte = true;
                message = ("interne".equals(mode) ? "Broker de l'application" : "Connecté au broker " + broker);
                try { envoyer(ROOT_BROKER, broker(BROKER_FETCH_CLIENT_LIST, new byte[0])); }
                catch (IOException ignore) { }
                break;
            }
            case BROKER_CONNECTED_CLIENT_LIST:
            case BROKER_CLIENT_ADD:
            case BROKER_CLIENT_ENTRY_CHANGE:
                for (Client c : lireFiches(b, d, p[2])) {
                    if (Arrays.equals(c.cid, cid)) continue;       // nous-mêmes
                    boolean neuf = !clients.containsKey(c.texte());
                    clients.put(c.texte(), c);
                    if (c.type == RPT_CLIENT_DEVICE && (neuf || vec == BROKER_CLIENT_ENTRY_CHANGE))
                        rdm.explorerRdmnet(c.uid, "RDMnet " + c.texte());
                }
                break;
            case BROKER_CLIENT_REMOVE:
                for (Client c : lireFiches(b, d, p[2])) {
                    clients.remove(c.texte());
                    rdm.oublierRdmnet(c.uid);
                }
                break;
            case BROKER_DISCONNECT:
                message = "Le broker a coupé la connexion";
                fermer();
                break;
            default:
                break;
        }
    }

    static List<Client> lireFiches(byte[] b, int o, int fin) {
        List<Client> l = new ArrayList<Client>();
        for (int[] p : pdus(b, o, fin)) {
            if (p[2] - p[1] < 4 + 16 + 6 + 1) continue;
            if (ByteBuffer.wrap(b, p[1], 4).getInt() != CLIENT_PROTOCOL_RPT) continue;
            Client c = new Client();
            System.arraycopy(b, p[1] + 4, c.cid, 0, 16);
            System.arraycopy(b, p[1] + 20, c.uid, 0, 6);
            c.type = b[p[1] + 26] & 255;
            l.add(c);
        }
        return l;
    }

    private void rptRecu(byte[] b, int[] p) {
        if (p[2] - p[1] < 4 + 21) return;
        int vec = ByteBuffer.wrap(b, p[1], 4).getInt();
        int h = p[1] + 4;
        long seq = ByteBuffer.wrap(b, h + 16, 4).getInt() & 0xFFFFFFFFL;
        LinkedBlockingQueue<Object> q = attente.get(seq);
        if (q == null) return;                           // notification spontanée : rien ne l'attend
        int d = h + 21;
        if (vec == RPT_STATUS) {
            for (int[] s : pdus(b, d, p[2])) {
                int code = u16(b, s[1]);
                String texte = new String(b, s[1] + 2, s[2] - s[1] - 2, StandardCharsets.UTF_8).trim();
                q.offer(Rdm.Reponse.echec(statut(code) + (texte.isEmpty() ? "" : " (" + texte + ")")));
            }
        } else if (vec == RPT_NOTIFICATION) {
            List<Rdm.Reponse> l = new ArrayList<Rdm.Reponse>();
            for (int[] n : pdus(b, d, p[2])) {
                if (ByteBuffer.wrap(b, n[1], 4).getInt() != NOTIFICATION_RDM_CMD) continue;
                for (int[] r : pdus(b, n[1] + 4, n[2])) {
                    if ((b[r[1]] & 255) != 0xCC) continue;
                    Rdm.Reponse x = Rdm.lireMessage(Arrays.copyOfRange(b, r[1] + 1, r[2]), 0);
                    if (x != null) l.add(x);
                }
            }
            q.offer(l);
        }
    }

    /* ------------------------------ transport --------------------------- */

    /**
     * Envoie la commande à l'appareil RPT (et au port) qui porte l'appareil
     * RDM, et attend la notification qui répond à ce numéro de séquence.
     */
    public Rdm.Reponse echanger(Rdm.Appareil a, int tn, int cc, int pid, byte[] pd) {
        if (!connecte) return Rdm.Reponse.echec("broker RDMnet non connecté");
        long seq;
        synchronized (this) { seq = sequence++; if (sequence > 0xFFFFFFFFL) sequence = 1; }
        LinkedBlockingQueue<Object> q = new LinkedBlockingQueue<Object>();
        attente.put(seq, q);
        try {
            byte[] m = Rdm.messageRdm(a.uidOctets, uid, tn, cc, pid, pd);
            envoyer(ROOT_RPT, rpt(RPT_REQUEST, uid, 0, a.rptUid, a.endpoint, seq,
                    pdu4(REQUEST_RDM_CMD, commandeRdm(m))));
            long fin = System.currentTimeMillis() + ATTENTE_MS;
            while (true) {
                long reste = fin - System.currentTimeMillis();
                if (reste <= 0) return null;
                Object o = q.poll(reste, TimeUnit.MILLISECONDS);
                if (o == null) return null;
                if (o instanceof Rdm.Reponse) return (Rdm.Reponse) o;
                Rdm.Reponse r = recoller((List<?>) o, a.uidOctets, cc, pid);
                if (r != null) return r;
            }
        } catch (IOException e) {
            return Rdm.Reponse.echec("envoi impossible");
        } catch (InterruptedException e) {
            return Rdm.Reponse.echec("interrompu");
        } finally {
            attente.remove(seq);
        }
    }

    /**
     * Une notification peut porter la commande d'origine, puis une réponse ou
     * plusieurs morceaux d'ACK_OVERFLOW : on ne garde que les réponses de cet
     * appareil à ce paramètre, recollées.
     */
    static Rdm.Reponse recoller(List<?> l, byte[] src, int cc, int pid) {
        ByteArrayOutputStream cumul = new ByteArrayOutputStream();
        Rdm.Reponse der = null;
        for (Object o : l) {
            Rdm.Reponse r = (Rdm.Reponse) o;
            if (!r.de(src) || r.pid != pid || r.cc != cc + 1) continue;
            cumul.write(r.donnees, 0, r.donnees.length);
            der = r;
        }
        if (der == null) return null;
        der.donnees = cumul.toByteArray();
        return der;
    }

    static byte[] commandeRdm(byte[] m) {
        // Le vecteur de la PDU tient lieu de code de départ : 0xCC, puis le reste du message.
        ByteBuffer b = ByteBuffer.allocate(3 + m.length);
        longueur(b, 3 + m.length);
        b.put(m);
        return b.array();
    }

    static byte[] rpt(int vecteur, byte[] src, int srcEp, byte[] dst, int dstEp, long seq, byte[] donnees) {
        int n = 3 + 4 + 21 + donnees.length;
        ByteBuffer b = ByteBuffer.allocate(n);
        longueur(b, n);
        b.putInt(vecteur);
        b.put(src).putShort((short) srcEp).put(dst).putShort((short) dstEp);
        b.putInt((int) seq).put((byte) 0);
        b.put(donnees);
        return b.array();
    }

    static byte[] pdu4(int vecteur, byte[] donnees) {
        ByteBuffer b = ByteBuffer.allocate(7 + donnees.length);
        longueur(b, 7 + donnees.length);
        b.putInt(vecteur).put(donnees);
        return b.array();
    }

    static byte[] broker(int vecteur, byte[] donnees) {
        ByteBuffer b = ByteBuffer.allocate(5 + donnees.length);
        longueur(b, 5 + donnees.length);
        b.putShort((short) vecteur).put(donnees);
        return b.array();
    }

    private void envoyer(int vecteurRacine, byte[] donnees) throws IOException {
        OutputStream s = sortie;
        if (s == null) throw new IOException("pas de connexion");
        byte[] m = message(vecteurRacine, cid, donnees);
        synchronized (s) { s.write(m); s.flush(); }
    }

    /* ------------------------------- trames ----------------------------- */

    /** Préambule TCP (identifiant ACN + taille du bloc) et PDU racine. */
    static byte[] message(int vecteurRacine, byte[] cid, byte[] donnees) {
        int racine = 3 + 4 + 16 + donnees.length;
        ByteBuffer b = ByteBuffer.allocate(16 + racine);
        b.put(ACN).putInt(racine);
        longueur(b, racine);
        b.putInt(vecteurRacine).put(cid).put(donnees);
        return b.array();
    }

    static byte[] lireBloc(DataInputStream in) throws IOException {
        byte[] id = new byte[12];
        in.readFully(id);
        if (!Arrays.equals(id, ACN)) throw new IOException("flux RDMnet illisible");
        int n = in.readInt();
        if (n < 0 || n > 1 << 20) throw new IOException("bloc trop grand");
        byte[] b = new byte[n];
        in.readFully(b);
        return b;
    }

    static void longueur(ByteBuffer b, int l) {
        b.put((byte) (0xF0 | ((l >> 16) & 0x0F))).put((byte) (l >> 8)).put((byte) l);
    }

    /**
     * Découpe une suite de PDU. Chaque entrée : { début, début du vecteur, fin }.
     * Tolère les longueurs sur deux octets (drapeau L absent).
     */
    static List<int[]> pdus(byte[] b, int o, int fin) {
        List<int[]> l = new ArrayList<int[]>();
        while (o + 2 <= fin) {
            boolean long3 = (b[o] & 0x80) != 0;
            int n = long3 ? (((b[o] & 0x0F) << 16) | ((b[o + 1] & 255) << 8) | (b[o + 2] & 255))
                          : (((b[o] & 0x0F) << 8) | (b[o + 1] & 255));
            if (n < (long3 ? 3 : 2) || o + n > fin) break;
            l.add(new int[] { o, o + (long3 ? 3 : 2), o + n });
            o += n;
        }
        return l;
    }

    static int u16(byte[] b, int i) { return ((b[i] & 255) << 8) | (b[i + 1] & 255); }

    static byte[] octets(UUID u) {
        return ByteBuffer.allocate(16).putLong(u.getMostSignificantBits())
                .putLong(u.getLeastSignificantBits()).array();
    }

    static String refus(int code) {
        switch (code) {
            case 1: return "portée différente";
            case 2: return "broker plein";
            case 3: return "UID déjà pris";
            case 4: return "fiche client invalide";
            case 5: return "UID invalide";
            default: return "code " + code;
        }
    }

    static String statut(int code) {
        switch (code) {
            case 0x0001: return "appareil RDMnet inconnu du broker";
            case 0x0002: return "pas de réponse sur la ligne DMX";
            case 0x0003: return "réponse RDM invalide";
            case 0x0004: return "projecteur inconnu de la passerelle";
            case 0x0005: return "port inconnu de la passerelle";
            case 0x0006: return "diffusion faite";
            case 0x0007: return "commande non comprise";
            case 0x0008: return "message invalide";
            case 0x0009: return "classe de commande invalide";
            default: return "statut RPT " + code;
        }
    }

    public byte[] cid() { return cid; }
}
