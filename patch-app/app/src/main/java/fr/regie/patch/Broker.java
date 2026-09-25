package fr.regie.patch;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
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

import static fr.regie.patch.Rdmnet.*;

/**
 * Un broker RDMnet (E1.33) minimal, pour un plateau qui n'en a pas.
 *
 * Il accepte les clients RPT de la portée « default », leur donne un UID
 * s'ils en demandent un, tient la liste des connectés et la diffuse aux
 * contrôleurs, puis fait suivre les messages RPT d'un client à l'autre sans
 * les modifier. Les appareils le trouvent par mDNS (Mdns l'annonce).
 */
public class Broker {

    static final byte[] TOUS_CONTROLEURS = { (byte) 0xFF, (byte) 0xFC, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF };
    static final byte[] TOUS_APPAREILS = { (byte) 0xFF, (byte) 0xFD, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF };

    class Connexion {
        final Socket s;
        final OutputStream sortie;
        Client fiche;
        byte[] ficheBrute;
        Connexion(Socket s) throws IOException { this.s = s; sortie = s.getOutputStream(); }
        void envoyer(byte[] m) {
            try { synchronized (sortie) { sortie.write(m); sortie.flush(); } }
            catch (IOException e) { try { s.close(); } catch (Exception ignore) { } }
        }
    }

    public final Map<String, Connexion> connectes = new ConcurrentHashMap<String, Connexion>();
    public final byte[] cid = octets(UUID.randomUUID());
    public final byte[] uid = new byte[6];
    public volatile boolean actif;
    public volatile int port;

    private ServerSocket serveur;
    private int prochainDynamique = 1;

    public Broker() {
        Random x = new Random();
        uid[0] = 0x7F; uid[1] = (byte) 0xF2;
        for (int i = 2; i < 6; i++) uid[i] = (byte) x.nextInt(256);
    }

    /** Ouvre le port d'écoute ; rend le port pris, ou -1. */
    public synchronized int demarrer(int portVoulu) {
        if (actif) return port;
        try {
            serveur = new ServerSocket();
            serveur.setReuseAddress(true);
            try { serveur.bind(new InetSocketAddress(portVoulu)); }
            catch (IOException e) { serveur.bind(new InetSocketAddress(0)); }
            port = serveur.getLocalPort();
        } catch (IOException e) { return -1; }
        actif = true;
        Thread t = new Thread(new Runnable() {
            public void run() {
                while (actif) {
                    try {
                        final Socket s = serveur.accept();
                        Thread c = new Thread(new Runnable() { public void run() { servir(s); } }, "broker-client");
                        c.setDaemon(true);
                        c.start();
                    } catch (IOException e) { if (!actif) return; }
                }
            }
        }, "broker");
        t.setDaemon(true);
        t.start();
        Thread coeur = new Thread(new Runnable() {
            public void run() {
                byte[] nul = message(ROOT_BROKER, cid, broker(BROKER_NULL, new byte[0]));
                while (actif) {
                    try { Thread.sleep(10000); } catch (InterruptedException e) { return; }
                    for (Connexion c : connectes.values()) c.envoyer(nul);
                }
            }
        }, "broker-coeur");
        coeur.setDaemon(true);
        coeur.start();
        return port;
    }

    public synchronized void arreter() {
        actif = false;
        try { if (serveur != null) serveur.close(); } catch (Exception ignore) { }
        for (Connexion c : connectes.values()) try { c.s.close(); } catch (Exception ignore) { }
        connectes.clear();
    }

    public int appareils() {
        int n = 0;
        for (Connexion c : connectes.values()) if (c.fiche.type == RPT_CLIENT_DEVICE) n++;
        return n;
    }

    private void servir(Socket s) {
        Connexion c = null;
        try {
            s.setSoTimeout(45000);                       // trois battements de cœur manqués
            s.setTcpNoDelay(true);
            c = new Connexion(s);
            DataInputStream in = new DataInputStream(s.getInputStream());
            while (actif && !s.isClosed()) {
                byte[] bloc = lireBloc(in);
                for (int[] r : pdus(bloc, 0, bloc.length)) {
                    int vec = ByteBuffer.wrap(bloc, r[1], 4).getInt();
                    int o = r[1] + 4 + 16;
                    if (vec == ROOT_BROKER) {
                        for (int[] p : pdus(bloc, o, r[2])) if (!brokerRecu(c, bloc, p)) return;
                    } else if (vec == ROOT_RPT && c.fiche != null) {
                        for (int[] p : pdus(bloc, o, r[2])) router(c, bloc, p);
                    }
                }
            }
        } catch (Exception ignore) {
        } finally {
            try { s.close(); } catch (Exception ignore) { }
            if (c != null && c.fiche != null && connectes.get(c.fiche.texte()) == c) {
                connectes.remove(c.fiche.texte());
                diffuser(BROKER_CLIENT_REMOVE, c.ficheBrute);
            }
        }
    }

    /** Rend false quand la connexion doit être fermée. */
    private boolean brokerRecu(Connexion c, byte[] b, int[] p) {
        int vec = u16(b, p[1]);
        int d = p[1] + 2;
        switch (vec) {
            case BROKER_CONNECT: {
                if (p[2] - d < 63 + 2 + 231 + 1 + 3) return repondre(c, 4, new byte[6]);
                String portee = texte(b, d, 63);
                List<int[]> fiches = pdus(b, d + 63 + 2 + 231 + 1, p[2]);
                List<Client> l = lireFiches(b, d + 63 + 2 + 231 + 1, p[2]);
                if (l.isEmpty() || fiches.isEmpty()) return repondre(c, 4, new byte[6]);
                if (!PORTEE.equals(portee)) return repondre(c, 1, l.get(0).uid);
                Client f = l.get(0);
                if ((f.uid[0] & 0x80) != 0 && f.uid[2] == 0 && f.uid[3] == 0 && f.uid[4] == 0 && f.uid[5] == 0) {
                    int n;
                    synchronized (this) { n = prochainDynamique++; }
                    f.uid[2] = (byte) (n >> 24); f.uid[3] = (byte) (n >> 16);
                    f.uid[4] = (byte) (n >> 8); f.uid[5] = (byte) n;
                }
                if (connectes.containsKey(f.texte())) return repondre(c, 3, f.uid);
                c.fiche = f;
                c.ficheBrute = Rdmnet.ficheClient(f.cid, f.uid, f.type);
                repondre(c, 0, f.uid);
                connectes.put(f.texte(), c);
                diffuser(BROKER_CLIENT_ADD, c.ficheBrute);
                return true;
            }
            case BROKER_FETCH_CLIENT_LIST: {
                java.io.ByteArrayOutputStream l = new java.io.ByteArrayOutputStream();
                for (Connexion x : connectes.values()) l.write(x.ficheBrute, 0, x.ficheBrute.length);
                c.envoyer(message(ROOT_BROKER, cid, broker(BROKER_CONNECTED_CLIENT_LIST, l.toByteArray())));
                return true;
            }
            case BROKER_REQUEST_DYNAMIC_UIDS: {
                java.io.ByteArrayOutputStream l = new java.io.ByteArrayOutputStream();
                for (int o = d; o + 22 <= p[2]; o += 22) {
                    byte[] u = Arrays.copyOfRange(b, o, o + 6);
                    int n;
                    synchronized (this) { n = prochainDynamique++; }
                    u[2] = (byte) (n >> 24); u[3] = (byte) (n >> 16); u[4] = (byte) (n >> 8); u[5] = (byte) n;
                    l.write(u, 0, 6);
                    l.write(b, o + 6, 16);
                    l.write(0); l.write(0);                    // accordé
                }
                c.envoyer(message(ROOT_BROKER, cid, broker(BROKER_ASSIGNED_DYNAMIC_UIDS, l.toByteArray())));
                return true;
            }
            case BROKER_DISCONNECT:
                return false;
            default:
                return true;                                   // NULL, mises à jour de fiche : rien à faire
        }
    }

    private boolean repondre(Connexion c, int code, byte[] clientUid) {
        ByteBuffer r = ByteBuffer.allocate(16);
        r.putShort((short) code).putShort((short) 1).put(uid).put(clientUid);
        c.envoyer(message(ROOT_BROKER, cid, broker(BROKER_CONNECT_REPLY, r.array())));
        return code == 0;
    }

    /** Liste des connectés mise à jour : prévenir les contrôleurs. */
    private void diffuser(int vecteur, byte[] fiche) {
        byte[] m = message(ROOT_BROKER, cid, broker(vecteur, fiche));
        for (Connexion x : connectes.values())
            if (x.fiche.type == RPT_CLIENT_CONTROLLER) x.envoyer(m);
    }

    /** Fait suivre un message RPT à son destinataire, ou à tous ceux de son type. */
    private void router(Connexion de, byte[] b, int[] p) {
        if (p[2] - p[1] < 4 + 21) return;
        int vec = ByteBuffer.wrap(b, p[1], 4).getInt();
        int h = p[1] + 4;
        byte[] dst = Arrays.copyOfRange(b, h + 8, h + 14);
        byte[] m = message(ROOT_RPT, de.fiche.cid, Arrays.copyOfRange(b, p[0], p[2]));
        List<Connexion> cibles = new ArrayList<Connexion>();
        if (Arrays.equals(dst, TOUS_CONTROLEURS)) {
            for (Connexion x : connectes.values()) if (x != de && x.fiche.type == RPT_CLIENT_CONTROLLER) cibles.add(x);
        } else if (Arrays.equals(dst, TOUS_APPAREILS)) {
            for (Connexion x : connectes.values()) if (x != de && x.fiche.type == RPT_CLIENT_DEVICE) cibles.add(x);
        } else {
            Connexion x = connectes.get(Rdm.uidTexte(dst));
            if (x != null) cibles.add(x);
        }
        if (cibles.isEmpty() && vec == RPT_REQUEST) {
            // Destinataire inconnu : on le dit à l'expéditeur, sur son numéro de séquence.
            ByteBuffer st = ByteBuffer.allocate(5);
            Rdmnet.longueur(st, 5);
            st.putShort((short) 0x0001);
            byte[] src = Arrays.copyOfRange(b, h, h + 6);
            int srcEp = u16(b, h + 6), dstEp = u16(b, h + 14);
            long seq = ByteBuffer.wrap(b, h + 16, 4).getInt() & 0xFFFFFFFFL;
            de.envoyer(message(ROOT_RPT, cid, rpt(RPT_STATUS, dst, dstEp, src, srcEp, seq, st.array())));
            return;
        }
        for (Connexion x : cibles) x.envoyer(m);
    }

    private static String texte(byte[] b, int o, int max) {
        int n = 0;
        while (n < max && b[o + n] != 0) n++;
        return new String(b, o, n, StandardCharsets.UTF_8);
    }
}
