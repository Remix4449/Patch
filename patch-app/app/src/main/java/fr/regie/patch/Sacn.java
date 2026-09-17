package fr.regie.patch;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * sACN / E1.31 : écoute d'un univers en multicast et recensement des sources
 * par l'univers de découverte (64214). Port UDP 5568.
 *
 * Un univers n'est reçu que si l'on a rejoint son groupe multicast : le
 * recensement passe donc par la découverte, que les sources émettent toutes
 * les dix secondes.
 */
public class Sacn {

    private static final int PORT = 5568;
    private static final int UNIVERS_DECOUVERTE = 64214;
    private static final byte[] ACN = {
        0x41, 0x53, 0x43, 0x2d, 0x45, 0x31, 0x2e, 0x31, 0x37, 0x00, 0x00, 0x00 };  // "ASC-E1.17"

    public static class Uni {
        public int univers;
        public String src = "", nom = "";
        public int prio, hz, trames;
        public long vu, fenetre;
    }

    public final Map<Integer, Uni> univers = new ConcurrentHashMap<Integer, Uni>();
    public final byte[] niveaux = new byte[512];
    public volatile int ecoute = -1;
    public volatile String ecouteSrc = "", ecouteNom = "";
    public volatile int ecoutePrio = 0, ecouteHz = 0;

    private MulticastSocket sock;
    private NetworkInterface nif;
    private Thread boucle;
    private volatile boolean actif;
    private int rejoint = -1;
    private int trames; private long fenetre;

    public synchronized void demarrer(String ipLocale) {
        if (actif) return;
        actif = true;
        try { nif = NetworkInterface.getByInetAddress(InetAddress.getByName(ipLocale)); }
        catch (Exception e) { nif = null; }
        boucle = new Thread(new Runnable() { public void run() { ecouter(); } }, "sacn");
        boucle.setDaemon(true);
        boucle.start();
    }

    private void ecouter() {
        try {
            sock = new MulticastSocket(null);
            sock.setReuseAddress(true);
            sock.bind(new InetSocketAddress(PORT));
            sock.setSoTimeout(1000);
            if (nif != null) sock.setNetworkInterface(nif);
            rejoindre(groupe(UNIVERS_DECOUVERTE));
        } catch (Exception e) { actif = false; return; }

        byte[] tampon = new byte[1400];
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

    /** Rejoint le groupe de l'univers demandé et quitte le précédent. */
    public synchronized void suivre(int universBase1) {
        if (universBase1 == rejoint) { ecoute = universBase1; return; }
        try {
            if (rejoint > 0 && sock != null) quitter(groupe(rejoint));
            if (universBase1 > 0 && sock != null) rejoindre(groupe(universBase1));
            rejoint = universBase1;
            ecoute = universBase1;
            synchronized (niveaux) { java.util.Arrays.fill(niveaux, (byte) 0); }
        } catch (Exception ignore) { }
    }

    private static String groupe(int u) { return "239.255." + ((u >> 8) & 255) + "." + (u & 255); }

    private void rejoindre(String g) throws Exception {
        sock.joinGroup(new InetSocketAddress(InetAddress.getByName(g), PORT), nif);
    }

    private void quitter(String g) throws Exception {
        sock.leaveGroup(new InetSocketAddress(InetAddress.getByName(g), PORT), nif);
    }

    private void traiter(byte[] b, int len, String src) {
        if (len < 49) return;
        for (int i = 0; i < 12; i++) if (b[4 + i] != ACN[i]) return;
        int vecteurRacine = entier32(b, 18);
        int vecteurTrame = entier32(b, 40);
        String nom = texte(b, 44, 64);
        long t = System.currentTimeMillis();

        if (vecteurRacine == 0x00000004 && vecteurTrame == 0x00000002 && len >= 126) {
            int u = ((b[113] & 255) << 8) | (b[114] & 255);
            int prio = b[108] & 255;
            Uni e = univers.get(u);
            if (e == null) { e = new Uni(); e.univers = u; e.fenetre = t; univers.put(u, e); }
            e.src = src; e.nom = nom; e.prio = prio; e.vu = t; e.trames++;
            if (t - e.fenetre >= 1000) { e.hz = e.trames; e.trames = 0; e.fenetre = t; }

            if (u == ecoute && b[125] == 0) {      // code de départ nul = données DMX
                int nb = Math.max(0, (((b[123] & 255) << 8) | (b[124] & 255)) - 1);
                nb = Math.min(512, nb);
                synchronized (niveaux) {
                    java.util.Arrays.fill(niveaux, (byte) 0);
                    for (int i = 0; i < nb && 126 + i < len; i++) niveaux[i] = b[126 + i];
                }
                ecouteSrc = src; ecouteNom = nom; ecoutePrio = prio;
                trames++;
                if (t - fenetre >= 1000) { ecouteHz = trames; trames = 0; fenetre = t; }
            }
            return;
        }

        // Paquet de découverte : la liste des univers émis par cette source
        if (vecteurRacine == 0x00000008 && len >= 122) {
            try {
                if (entier32(b, 114) != 0x00000001) return;
                for (int p = 120; p + 1 < len; p += 2) {
                    int u = ((b[p] & 255) << 8) | (b[p + 1] & 255);
                    if (u <= 0 || u > 63999) continue;
                    Uni e = univers.get(u);
                    if (e == null) { e = new Uni(); e.univers = u; e.fenetre = t; univers.put(u, e); }
                    e.src = src; e.nom = nom; e.vu = t;
                }
            } catch (Exception ignore) { }
        }
    }

    private void purger() {
        long t = System.currentTimeMillis();
        for (Map.Entry<Integer, Uni> e : univers.entrySet())
            if (t - e.getValue().vu > 15000) univers.remove(e.getKey());
    }

    private static int entier32(byte[] b, int p) {
        if (p + 3 >= b.length) return -1;
        return ((b[p] & 255) << 24) | ((b[p + 1] & 255) << 16)
             | ((b[p + 2] & 255) << 8) | (b[p + 3] & 255);
    }

    private static String texte(byte[] b, int pos, int max) {
        int n = 0;
        while (n < max && pos + n < b.length && b[pos + n] != 0) n++;
        return new String(b, pos, n).trim();
    }

    public void arreter() {
        actif = false;
        try { if (sock != null) sock.close(); } catch (Exception ignore) { }
        univers.clear();
        rejoint = -1;
    }
}
