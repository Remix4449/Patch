package fr.regie.patch;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Art-Net : découverte par ArtPoll, écoute des trames ArtDmx, émission.
 * Port UDP 6454. Les univers sont manipulés en adresse de port (base 0) et
 * exposés à l'interface en base 1, comme sur les pupitres.
 */
public class ArtNet {

    private static final byte[] ID = { 'A', 'r', 't', '-', 'N', 'e', 't', 0 };
    private static final int PORT = 6454;

    public static class Noeud {
        public String ip = "", court = "", longNom = "", mac = "", rapport = "";
        public int[] univers = new int[0];
        public long vu;
    }

    public static class Uni {
        public int univers;           // adresse de port, base 0
        public String src = "";
        public long vu;
        public int trames;
        public int hz;
        public long fenetre;
    }

    public final Map<String, Noeud> noeuds = new ConcurrentHashMap<String, Noeud>();
    public final Map<Integer, Uni> univers = new ConcurrentHashMap<Integer, Uni>();
    public final byte[] niveaux = new byte[512];
    public volatile int ecoute = -1;
    public volatile String ecouteSrc = "";
    public volatile int ecouteHz = 0;

    private DatagramSocket sock;
    private Thread boucle;
    private volatile boolean actif;
    private byte sequence = 1;
    private String diffusion = "255.255.255.255";
    private int trames; private long fenetre;

    public synchronized void demarrer(String adresseDiffusion) {
        if (adresseDiffusion != null && !adresseDiffusion.isEmpty()) diffusion = adresseDiffusion;
        if (actif) return;
        actif = true;
        boucle = new Thread(new Runnable() { public void run() { ecouter(); } }, "artnet");
        boucle.setDaemon(true);
        boucle.start();
    }

    private void ecouter() {
        try {
            sock = new DatagramSocket(null);
            sock.setReuseAddress(true);
            sock.setBroadcast(true);
            sock.bind(new InetSocketAddress(PORT));
            sock.setSoTimeout(1000);
        } catch (Exception e) { actif = false; return; }

        byte[] tampon = new byte[1200];
        while (actif) {
            try {
                DatagramPacket p = new DatagramPacket(tampon, tampon.length);
                sock.receive(p);
                traiter(p.getData(), p.getLength(), p.getAddress().getHostAddress());
            } catch (java.net.SocketTimeoutException t) {
                purger();
            } catch (Exception e) {
                if (!actif) break;
            }
        }
        try { if (sock != null) sock.close(); } catch (Exception ignore) { }
    }

    private void traiter(byte[] b, int len, String src) {
        if (len < 12) return;
        for (int i = 0; i < 8; i++) if (b[i] != ID[i]) return;
        int op = (b[8] & 255) | ((b[9] & 255) << 8);

        if (op == 0x2100 && len >= 207) {          // ArtPollReply
            Noeud n = new Noeud();
            n.ip = (b[10] & 255) + "." + (b[11] & 255) + "." + (b[12] & 255) + "." + (b[13] & 255);
            if (n.ip.equals("0.0.0.0")) n.ip = src;
            n.court = texte(b, 26, 18);
            n.longNom = texte(b, 44, 64);
            n.rapport = texte(b, 108, 64);
            int net = b[18] & 0x7F, sub = b[19] & 0x0F;
            int nb = Math.min(4, ((b[172] & 255) << 8) | (b[173] & 255));
            int[] u = new int[nb];
            for (int i = 0; i < nb; i++) u[i] = (net << 8) | (sub << 4) | (b[190 + i] & 0x0F);
            n.univers = u;
            StringBuilder m = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                if (i > 0) m.append(':');
                m.append(String.format("%02X", b[201 + i] & 255));
            }
            n.mac = m.toString();
            n.vu = System.currentTimeMillis();
            noeuds.put(n.ip, n);
            return;
        }

        if (op == 0x5000 && len >= 18) {           // ArtDmx
            int u = ((b[15] & 0x7F) << 8) | (b[14] & 255);
            long t = System.currentTimeMillis();
            Uni e = univers.get(u);
            if (e == null) { e = new Uni(); e.univers = u; e.fenetre = t; univers.put(u, e); }
            e.src = src; e.vu = t; e.trames++;
            if (t - e.fenetre >= 1000) { e.hz = e.trames; e.trames = 0; e.fenetre = t; }

            if (u == ecoute) {
                int n = Math.min(512, ((b[16] & 255) << 8) | (b[17] & 255));
                synchronized (niveaux) {
                    java.util.Arrays.fill(niveaux, (byte) 0);
                    for (int i = 0; i < n && 18 + i < len; i++) niveaux[i] = b[18 + i];
                }
                ecouteSrc = src;
                trames++;
                if (t - fenetre >= 1000) { ecouteHz = trames; trames = 0; fenetre = t; }
            }
        }
    }

    private void purger() {
        long t = System.currentTimeMillis();
        for (Map.Entry<Integer, Uni> e : univers.entrySet())
            if (t - e.getValue().vu > 6000) univers.remove(e.getKey());
        for (Map.Entry<String, Noeud> e : noeuds.entrySet())
            if (t - e.getValue().vu > 60000) noeuds.remove(e.getKey());
    }

    private static String texte(byte[] b, int pos, int max) {
        int n = 0;
        while (n < max && pos + n < b.length && b[pos + n] != 0) n++;
        return new String(b, pos, n).trim();
    }

    /** Envoie un ArtPoll en diffusion : chaque nœud répond avec sa fiche. */
    public void interroger() {
        byte[] p = new byte[14];
        System.arraycopy(ID, 0, p, 0, 8);
        p[8] = 0x00; p[9] = 0x20;      // OpPoll
        p[10] = 0x00; p[11] = 0x0e;    // version de protocole 14
        p[12] = 0x02;                  // réponse à chaque changement d'état
        p[13] = 0x00;
        envoyerBrut(p, diffusion);
        envoyerBrut(p, "255.255.255.255");
    }

    /** Émet une trame de 512 niveaux sur un univers (adresse de port, base 0). */
    public boolean emettre(int portAddress, byte[] data, String cible) {
        byte[] p = new byte[18 + 512];
        System.arraycopy(ID, 0, p, 0, 8);
        p[8] = 0x00; p[9] = 0x50;      // OpDmx
        p[10] = 0x00; p[11] = 0x0e;
        p[12] = sequence++; if (sequence == 0) sequence = 1;
        p[13] = 0x00;
        p[14] = (byte) (portAddress & 0xFF);
        p[15] = (byte) ((portAddress >> 8) & 0x7F);
        p[16] = (byte) 0x02; p[17] = (byte) 0x00;   // longueur 512
        System.arraycopy(data, 0, p, 18, Math.min(512, data.length));
        return envoyerBrut(p, (cible == null || cible.isEmpty()) ? diffusion : cible);
    }

    private boolean envoyerBrut(byte[] p, String cible) {
        try {
            if (sock == null) return false;
            sock.send(new DatagramPacket(p, p.length, InetAddress.getByName(cible), PORT));
            return true;
        } catch (Exception e) { return false; }
    }

    public void arreter() {
        actif = false;
        try { if (sock != null) sock.close(); } catch (Exception ignore) { }
        noeuds.clear(); univers.clear();
    }
}
