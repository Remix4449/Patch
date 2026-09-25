package fr.regie.patch;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Le strict nécessaire de mDNS / DNS-SD pour RDMnet (E1.33, section 9) :
 * chercher les brokers annoncés sous « _default._sub._rdmnet._tcp.local », et
 * annoncer le nôtre quand l'application en sert un.
 *
 * On écrit le nôtre plutôt que de passer par NsdManager : Android n'annonce
 * les sous-types qu'à partir des versions récentes, et c'est justement le
 * sous-type de la portée que les appareils RDMnet interrogent.
 */
public class Mdns {

    static final String SERVICE = "_rdmnet._tcp.local";
    static final String SOUS_TYPE = "_default._sub._rdmnet._tcp.local";
    private static final int PORT = 5353;
    private static final String GROUPE = "224.0.0.251";
    static final int A = 1, PTR = 12, TXT = 16, SRV = 33, ANY = 255;

    public static class Trouve {
        public String instance = "", ip = "", portee = "default", cid = "";
        public int port;
    }

    /* Ce qu'on a entendu passer, par nom. */
    private final Map<String, List<String>> ptr = new ConcurrentHashMap<String, List<String>>();
    private final Map<String, Object[]> srv = new ConcurrentHashMap<String, Object[]>();   // { port, cible }
    private final Map<String, Map<String, String>> txt = new ConcurrentHashMap<String, Map<String, String>>();
    private final Map<String, String> adresses = new ConcurrentHashMap<String, String>();
    private final Map<String, String> sources = new ConcurrentHashMap<String, String>();   // instance → IP d'où vient l'annonce

    private MulticastSocket sock;
    private InetAddress groupe;
    private volatile boolean actif;

    /* Notre annonce, quand on sert un broker. */
    private volatile String instance, hote, ip, cidHex = "", uidHex = "";
    private volatile int port;

    public synchronized void demarrer(String ipLocale) throws Exception {
        if (actif) return;
        groupe = InetAddress.getByName(GROUPE);
        MulticastSocket s = new MulticastSocket(null);
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(PORT));
        s.setTimeToLive(255);
        NetworkInterface nif = null;
        try {
            if (ipLocale != null && !ipLocale.isEmpty())
                nif = NetworkInterface.getByInetAddress(InetAddress.getByName(ipLocale));
        } catch (Exception ignore) { }
        if (nif != null) {
            s.setNetworkInterface(nif);
            s.joinGroup(new InetSocketAddress(groupe, PORT), nif);
        } else {
            s.joinGroup(new InetSocketAddress(groupe, PORT), null);
        }
        sock = s;
        actif = true;
        Thread t = new Thread(new Runnable() { public void run() { ecouter(); } }, "mdns");
        t.setDaemon(true);
        t.start();
    }

    public synchronized void arreter() {
        if (instance != null) try { envoyer(annonce(0), null); } catch (Exception ignore) { }
        instance = null;
        actif = false;
        try { if (sock != null) sock.close(); } catch (Exception ignore) { }
    }

    private void ecouter() {
        byte[] tampon = new byte[9000];
        while (actif) {
            try {
                DatagramPacket p = new DatagramPacket(tampon, tampon.length);
                sock.receive(p);
                byte[] b = java.util.Arrays.copyOf(p.getData(), p.getLength());
                if ((b[2] & 0x80) != 0) lireReponse(b, p.getAddress().getHostAddress());
                else if (instance != null) repondre(b, (InetSocketAddress) p.getSocketAddress());
            } catch (Exception e) {
                if (!actif) return;
            }
        }
    }

    /* ------------------------------ recherche ---------------------------- */

    /** Pose la question trois fois et rend les brokers de la portée « default », sauf nous. */
    public List<Trouve> chercher(long dureeMs) throws Exception {
        byte[] q = question(SOUS_TYPE, PTR);
        long pas = Math.max(300, dureeMs / 3);
        for (int i = 0; i < 3; i++) {
            envoyer(q, null);
            Thread.sleep(pas);
        }
        List<Trouve> l = new ArrayList<Trouve>();
        List<String> noms = new ArrayList<String>();
        for (String k : new String[] { SOUS_TYPE, SERVICE }) {
            List<String> x = ptr.get(k.toLowerCase());
            if (x != null) for (String n : x) if (!noms.contains(n)) noms.add(n);
        }
        for (String n : noms) {
            Object[] s = srv.get(n.toLowerCase());
            if (s == null) continue;
            Trouve t = new Trouve();
            t.instance = n.replace("." + SERVICE, "");
            t.port = (Integer) s[0];
            String a = adresses.get(((String) s[1]).toLowerCase());
            t.ip = a != null ? a : sources.getOrDefault(n.toLowerCase(), "");
            Map<String, String> m = txt.get(n.toLowerCase());
            if (m != null) {
                if (m.containsKey("E133Scope")) t.portee = m.get("E133Scope");
                if (m.containsKey("CID")) t.cid = m.get("CID").replace("-", "").toLowerCase();
            }
            if (t.ip.isEmpty() || !Rdmnet.PORTEE.equals(t.portee)) continue;
            if (!cidHex.isEmpty() && cidHex.equals(t.cid)) continue;       // notre propre broker
            l.add(t);
        }
        return l;
    }

    static byte[] question(String nom, int type) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        entete(b, 0, 0x0000, 1, 0, 0);
        nom(b, nom);
        u16(b, type); u16(b, 1);
        return b.toByteArray();
    }

    void lireReponse(byte[] b, String src) {
        if (b.length < 12) return;
        int qd = lu16(b, 4), an = lu16(b, 6), ns = lu16(b, 8), ar = lu16(b, 10);
        int[] o = { 12 };
        for (int i = 0; i < qd; i++) { lireNom(b, o); o[0] += 4; }
        for (int i = 0; i < an + ns + ar && o[0] < b.length; i++) {
            String n = lireNom(b, o);
            int type = lu16(b, o[0]);
            int lg = lu16(b, o[0] + 8);
            int d = o[0] + 10;
            o[0] = d + lg;
            if (o[0] > b.length) return;
            String cle = n.toLowerCase();
            if (type == PTR) {
                int[] x = { d };
                String cible = lireNom(b, x);
                List<String> l = ptr.get(cle);
                if (l == null) { l = new java.util.concurrent.CopyOnWriteArrayList<String>(); ptr.put(cle, l); }
                if (!l.contains(cible)) l.add(cible);
                sources.put(cible.toLowerCase(), src);
            } else if (type == SRV) {
                int[] x = { d + 6 };
                srv.put(cle, new Object[] { lu16(b, d + 4), lireNom(b, x) });
                sources.put(cle, src);
            } else if (type == TXT) {
                Map<String, String> m = new HashMap<String, String>();
                for (int k = d; k < d + lg; ) {
                    int l = b[k] & 255;
                    String e = new String(b, k + 1, Math.min(l, d + lg - k - 1), StandardCharsets.UTF_8);
                    int eg = e.indexOf('=');
                    if (eg > 0) m.put(e.substring(0, eg), e.substring(eg + 1));
                    k += l + 1;
                }
                txt.put(cle, m);
            } else if (type == A && lg == 4) {
                adresses.put(cle, (b[d] & 255) + "." + (b[d + 1] & 255) + "." + (b[d + 2] & 255) + "." + (b[d + 3] & 255));
            }
        }
    }

    /* ------------------------------ annonce ----------------------------- */

    /** Annonce notre broker : deux envois spontanés, puis réponse aux questions. */
    public void annoncer(String nomInstance, String ipLocale, int portBroker, byte[] cid, byte[] uid) {
        StringBuilder c = new StringBuilder();
        for (byte x : cid) c.append(String.format("%02x", x & 255));
        StringBuilder u = new StringBuilder();
        for (byte x : uid) u.append(String.format("%02x", x & 255));
        cidHex = c.toString(); uidHex = u.toString();
        ip = ipLocale; port = portBroker;
        hote = "patch-" + uidHex.substring(8) + ".local";
        instance = nomInstance + "." + SERVICE;
        new Thread(new Runnable() {
            public void run() {
                for (int i = 0; i < 2 && instance != null; i++) {
                    try { envoyer(annonce(120), null); } catch (Exception ignore) { }
                    try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                }
            }
        }, "mdns-annonce").start();
    }

    private void repondre(byte[] b, InetSocketAddress de) throws Exception {
        if (b.length < 12) return;
        int qd = lu16(b, 4);
        int[] o = { 12 };
        boolean ok = false;
        for (int i = 0; i < qd && o[0] < b.length; i++) {
            String n = lireNom(b, o).toLowerCase();
            int type = lu16(b, o[0]);
            o[0] += 4;
            if ((type == PTR || type == ANY) && (n.equals(SERVICE) || n.equals(SOUS_TYPE))) ok = true;
            if ((type == SRV || type == TXT || type == ANY) && n.equals(instance.toLowerCase())) ok = true;
            if ((type == A || type == ANY) && n.equals(hote)) ok = true;
        }
        if (!ok) return;
        byte[] r = annonce(120);
        // Une question posée depuis un autre port que 5353 attend sa réponse en direct.
        envoyer(r, de.getPort() != PORT ? de : null);
    }

    /** PTR (service et sous-type), SRV, TXT et A de notre broker. ttl 0 : retrait. */
    byte[] annonce(int ttl) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        entete(b, 0, 0x8400, 0, 5, 0);
        enregistrement(b, SERVICE, PTR, false, ttl * 37, nomOctets(instance));
        enregistrement(b, SOUS_TYPE, PTR, false, ttl * 37, nomOctets(instance));
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        u16(s, 0); u16(s, 0); u16(s, port);
        byte[] h = nomOctets(hote);
        s.write(h, 0, h.length);
        enregistrement(b, instance, SRV, true, ttl, s.toByteArray());
        ByteArrayOutputStream t = new ByteArrayOutputStream();
        for (String e : new String[] { "TxtVers=1", "E133Scope=" + Rdmnet.PORTEE, "E133Vers=1",
                "CID=" + cidHex, "UID=" + uidHex, "Model=Patch", "Manuf=Patch" }) {
            byte[] x = e.getBytes(StandardCharsets.UTF_8);
            t.write(x.length); t.write(x, 0, x.length);
        }
        enregistrement(b, instance, TXT, true, ttl * 37, t.toByteArray());
        byte[] a = new byte[4];
        String[] p = ip.split("\\.");
        for (int i = 0; i < 4 && i < p.length; i++) a[i] = (byte) Integer.parseInt(p[i]);
        enregistrement(b, hote, A, true, ttl, a);
        return b.toByteArray();
    }

    private void envoyer(byte[] m, InetSocketAddress vers) throws Exception {
        MulticastSocket s = sock;
        if (s == null) return;
        s.send(vers != null ? new DatagramPacket(m, m.length, vers)
                            : new DatagramPacket(m, m.length, groupe, PORT));
    }

    /* ----------------------------- format DNS ---------------------------- */

    static void entete(ByteArrayOutputStream b, int id, int drapeaux, int qd, int an, int ar) {
        u16(b, id); u16(b, drapeaux); u16(b, qd); u16(b, an); u16(b, 0); u16(b, ar);
    }

    static void enregistrement(ByteArrayOutputStream b, String n, int type, boolean unique, int ttl, byte[] d) {
        nom(b, n);
        u16(b, type);
        u16(b, unique ? 0x8001 : 0x0001);
        u16(b, (ttl >> 16) & 0xFFFF); u16(b, ttl & 0xFFFF);
        u16(b, d.length);
        b.write(d, 0, d.length);
    }

    /**
     * Découpe en étiquettes. Le premier point sépare le nom d'instance, qui
     * peut contenir des espaces, du reste.
     */
    static byte[] nomOctets(String n) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        nom(b, n);
        return b.toByteArray();
    }

    static void nom(ByteArrayOutputStream b, String n) {
        for (String e : n.split("\\.")) {
            if (e.isEmpty()) continue;
            byte[] x = e.getBytes(StandardCharsets.UTF_8);
            b.write(Math.min(63, x.length));
            b.write(x, 0, Math.min(63, x.length));
        }
        b.write(0);
    }

    static String lireNom(byte[] b, int[] o) {
        StringBuilder s = new StringBuilder();
        int p = o[0], sauts = 0;
        boolean saute = false;
        while (p < b.length) {
            int l = b[p] & 255;
            if (l == 0) { p++; break; }
            if ((l & 0xC0) == 0xC0) {
                if (p + 1 >= b.length || ++sauts > 20) break;
                int cible = ((l & 0x3F) << 8) | (b[p + 1] & 255);
                if (!saute) { o[0] = p + 2; saute = true; }
                p = cible;
                continue;
            }
            if (s.length() > 0) s.append('.');
            s.append(new String(b, p + 1, Math.min(l, b.length - p - 1), StandardCharsets.UTF_8));
            p += l + 1;
        }
        if (!saute) o[0] = p;
        return s.toString();
    }

    static void u16(ByteArrayOutputStream b, int v) { b.write((v >> 8) & 255); b.write(v & 255); }
    static int lu16(byte[] b, int i) { return i + 1 < b.length ? ((b[i] & 255) << 8) | (b[i + 1] & 255) : 0; }
}
