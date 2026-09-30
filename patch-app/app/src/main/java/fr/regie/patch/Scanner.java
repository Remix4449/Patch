package fr.regie.patch;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Balayage du sous-réseau : ICMP quand le système l'autorise, sinon tentative
 * de connexion TCP sur les ports habituels d'un réseau de plateau.
 *
 * Un hôte trouvé est ajouté tout de suite, puis on lui demande son nom : un
 * réseau de régie n'a presque jamais de DNS, on interroge donc l'appareil
 * lui-même — NetBIOS pour les PC Windows (pupitres compris), mDNS pour les
 * Mac, Linux et beaucoup d'appareils récents, et à défaut le titre de sa page
 * web d'administration. Les noms annoncés en Art-Net, sACN ou NDI passent
 * avant, côté Regie.
 */
public class Scanner {

    public static class Hote {
        public String ip, role = "";
        public volatile String nom = "", origine = "";
        public double ms;
        public boolean ok = true;
    }

    public final List<Hote> hotes = new CopyOnWriteArrayList<Hote>();
    public final AtomicInteger faits = new AtomicInteger();
    public volatile int total = 254;
    public volatile boolean encours;

    private static final int[] PORTS = { 80, 443, 22, 8080 };
    private ExecutorService piscine;

    public void lancer(final String base) {
        if (encours || base == null || base.isEmpty()) return;
        hotes.clear(); faits.set(0); encours = true;
        piscine = Executors.newFixedThreadPool(48);
        for (int i = 1; i <= 254; i++) {
            final String ip = base + "." + i;
            piscine.execute(new Runnable() {
                public void run() {
                    Hote h = null;
                    try { h = sonder(ip); } catch (Exception ignore) { }
                    finally {
                        if (faits.incrementAndGet() >= 254) { encours = false; piscine.shutdown(); }
                    }
                    /* Après le compte : chercher un nom ne retient pas la fin
                       du balayage, l'écran relit l'état tant qu'il est ouvert. */
                    if (h != null) try { nommer(h); } catch (Exception ignore) { }
                }
            });
        }
    }

    private Hote sonder(String ip) throws Exception {
        long t0 = System.nanoTime();
        InetAddress a = InetAddress.getByName(ip);
        boolean vivant = false;
        try { vivant = a.isReachable(500); } catch (Exception ignore) { }
        if (!vivant) {
            for (int p : PORTS) {
                Socket s = new Socket();
                try {
                    s.connect(new InetSocketAddress(a, p), 350);
                    vivant = true;
                } catch (Exception ignore) {
                } finally { try { s.close(); } catch (Exception ignore) { } }
                if (vivant) break;
            }
        }
        if (!vivant) return null;
        Hote h = new Hote();
        h.ip = ip;
        h.ms = (System.nanoTime() - t0) / 1e6;
        hotes.add(h);
        return h;
    }

    private void nommer(Hote h) {
        String n = netbios(h.ip);
        if (!n.isEmpty()) { h.nom = n; h.origine = "NetBIOS"; return; }
        n = mdns(h.ip);
        if (!n.isEmpty()) { h.nom = n; h.origine = "mDNS"; return; }
        n = titreWeb(h.ip);
        if (!n.isEmpty()) { h.nom = n; h.origine = "page web"; }
    }

    /* ------------------------------ NetBIOS ------------------------------ */

    /** Demande d'état de nœud (RFC 1002, NBSTAT) : la table des noms du poste. */
    static String netbios(String ip) {
        byte[] q = new byte[50];
        q[0] = 0x4E; q[1] = 0x42;          // identifiant
        q[5] = 1;                          // une question
        q[12] = 32;                        // nom « * » encodé sur 32 caractères
        q[13] = 'C'; q[14] = 'K';
        for (int i = 15; i < 45; i++) q[i] = 'A';
        q[45] = 0;
        q[47] = 0x21;                      // NBSTAT
        q[49] = 0x01;                      // IN
        DatagramSocket s = null;
        try {
            s = new DatagramSocket();
            s.setSoTimeout(450);
            s.send(new DatagramPacket(q, q.length, InetAddress.getByName(ip), 137));
            byte[] r = new byte[1024];
            DatagramPacket p = new DatagramPacket(r, r.length);
            s.receive(p);
            int o = sauterNom(r, 12, p.getLength());
            o += 10;                       // type, classe, TTL, longueur
            if (o >= p.getLength()) return "";
            int nb = r[o] & 0xFF;
            o++;
            for (int i = 0; i < nb && o + 18 <= p.getLength(); i++, o += 18) {
                int suffixe = r[o + 15] & 0xFF;
                boolean groupe = (r[o + 16] & 0x80) != 0;
                if (suffixe == 0 && !groupe)
                    return new String(r, o, 15, StandardCharsets.US_ASCII).trim();
            }
        } catch (Exception ignore) {
        } finally { if (s != null) s.close(); }
        return "";
    }

    /* -------------------------------- mDNS ------------------------------- */

    /**
     * Question PTR inverse posée directement à l'hôte, sur le port 5353 :
     * un répondeur mDNS répond en unicast à une question venue d'un autre
     * port que 5353 (RFC 6762, 6.7).
     */
    static String mdns(String ip) {
        String[] b = ip.split("\\.");
        if (b.length != 4) return "";
        String nom = b[3] + "." + b[2] + "." + b[1] + "." + b[0] + ".in-addr.arpa";
        ByteArrayOutputStream q = new ByteArrayOutputStream();
        q.write(0x12); q.write(0x34);      // identifiant
        q.write(0); q.write(0);
        q.write(0); q.write(1);            // une question
        for (int i = 0; i < 6; i++) q.write(0);
        for (String l : nom.split("\\.")) {
            byte[] x = l.getBytes(StandardCharsets.US_ASCII);
            q.write(x.length); q.write(x, 0, x.length);
        }
        q.write(0);
        q.write(0); q.write(12);           // PTR
        q.write(0); q.write(1);            // IN
        byte[] d = q.toByteArray();
        DatagramSocket s = null;
        try {
            s = new DatagramSocket();
            s.setSoTimeout(450);
            s.send(new DatagramPacket(d, d.length, InetAddress.getByName(ip), 5353));
            byte[] r = new byte[1500];
            DatagramPacket p = new DatagramPacket(r, r.length);
            s.receive(p);
            int n = p.getLength();
            int qd = ((r[4] & 0xFF) << 8) | (r[5] & 0xFF);
            int an = ((r[6] & 0xFF) << 8) | (r[7] & 0xFF);
            int o = 12;
            for (int i = 0; i < qd; i++) o = sauterNom(r, o, n) + 4;
            for (int i = 0; i < an && o + 10 <= n; i++) {
                o = sauterNom(r, o, n);
                int type = ((r[o] & 0xFF) << 8) | (r[o + 1] & 0xFF);
                int lg = ((r[o + 8] & 0xFF) << 8) | (r[o + 9] & 0xFF);
                o += 10;
                if (type == 12) {
                    String h = lireNom(r, o, n);
                    if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
                    if (h.endsWith(".local")) h = h.substring(0, h.length() - 6);
                    return h;
                }
                o += lg;
            }
        } catch (Exception ignore) {
        } finally { if (s != null) s.close(); }
        return "";
    }

    /* ------------------------------ page web ----------------------------- */

    private static final Pattern TITRE = Pattern.compile("<title[^>]*>([^<]{1,80})</title>",
            Pattern.CASE_INSENSITIVE);

    /** Le titre de la page d'administration : souvent le modèle, parfois le nom donné. */
    static String titreWeb(String ip) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(ip, 80), 400);
            s.setSoTimeout(700);
            OutputStream out = s.getOutputStream();
            out.write(("GET / HTTP/1.0\r\nHost: " + ip + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = s.getInputStream();
            byte[] buf = new byte[16384];
            int lu = 0, k;
            while (lu < buf.length && (k = in.read(buf, lu, buf.length - lu)) > 0) lu += k;
            Matcher m = TITRE.matcher(new String(buf, 0, lu, StandardCharsets.ISO_8859_1));
            if (m.find()) return m.group(1).replaceAll("\\s+", " ").trim();
        } catch (Exception ignore) {
        } finally { try { s.close(); } catch (Exception ignore) { } }
        return "";
    }

    /* ------------------------ lecture des noms DNS ----------------------- */

    static int sauterNom(byte[] r, int o, int n) {
        while (o < n) {
            int l = r[o] & 0xFF;
            if (l == 0) return o + 1;
            if ((l & 0xC0) == 0xC0) return o + 2;
            o += l + 1;
        }
        return n;
    }

    static String lireNom(byte[] r, int o, int n) {
        StringBuilder sb = new StringBuilder();
        for (int saut = 0; o < n && saut < 16; ) {
            int l = r[o] & 0xFF;
            if (l == 0) break;
            if ((l & 0xC0) == 0xC0) {
                if (o + 1 >= n) break;
                o = ((l & 0x3F) << 8) | (r[o + 1] & 0xFF);
                saut++;
                continue;
            }
            if (o + 1 + l > n) break;
            if (sb.length() > 0) sb.append('.');
            sb.append(new String(r, o + 1, l, StandardCharsets.UTF_8));
            o += l + 1;
        }
        return sb.toString();
    }

    public void arreter() {
        encours = false;
        if (piscine != null) piscine.shutdownNow();
    }
}
