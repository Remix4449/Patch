package fr.regie.patch;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RDMnet, première marche : la découverte LLRP (E1.33, section 5).
 *
 * LLRP trouve tout appareil RDMnet du sous-réseau, même sans broker ni
 * adresse IP correcte : on pose la question en multicast, chaque appareil
 * répond avec son UID, son adresse matérielle et son rôle. On ne fait ici que
 * cette découverte ; le réglage des projecteurs derrière une passerelle RDMnet
 * passe par un broker, que l'application n'implémente pas.
 */
public class Llrp {

    private static final int PORT = 5569;
    private static final String GROUPE_DEMANDE = "239.255.250.133";
    private static final String GROUPE_REPONSE = "239.255.250.134";
    private static final byte[] DIFFUSION_CID = octets(UUID.fromString("fbad822c-bd0c-4d4c-bdc8-7eabebc85aff"));
    private static final byte[] CID = octets(UUID.randomUUID());

    public static class Cible {
        public String uid = "", mac = "", cid = "", ip = "", role = "";
        public long vu;
    }

    public final Map<String, Cible> cibles = new ConcurrentHashMap<String, Cible>();
    public volatile boolean encours;
    public volatile String erreur = "";
    private int transaction = 1;

    /** Trois tours de questions, chacun annonçant les UID déjà connus pour qu'ils se taisent. */
    public synchronized void decouvrir(final String ipLocale) {
        if (encours) return;
        encours = true; erreur = "";
        new Thread(new Runnable() {
            public void run() {
                try { tourner(ipLocale); }
                catch (Exception e) { erreur = "multicast indisponible : " + e.getMessage(); }
                finally { encours = false; }
            }
        }, "llrp").start();
    }

    private void tourner(String ipLocale) throws Exception {
        MulticastSocket s = new MulticastSocket(null);
        try {
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(PORT));
            s.setSoTimeout(250);
            InetAddress groupeReponse = InetAddress.getByName(GROUPE_REPONSE);
            NetworkInterface nif = null;
            try {
                if (ipLocale != null && !ipLocale.isEmpty())
                    nif = NetworkInterface.getByInetAddress(InetAddress.getByName(ipLocale));
            } catch (Exception ignore) { }
            if (nif != null) {
                s.setNetworkInterface(nif);
                s.joinGroup(new InetSocketAddress(groupeReponse, PORT), nif);
            } else {
                s.joinGroup(groupeReponse);
            }
            InetAddress demande = InetAddress.getByName(GROUPE_DEMANDE);
            byte[] tampon = new byte[1500];
            for (int tour = 0; tour < 3; tour++) {
                List<byte[]> connus = new ArrayList<byte[]>();
                for (Cible c : cibles.values()) connus.add(uidOctets(c.uid));
                byte[] p = demande(transaction++, connus);
                s.send(new DatagramPacket(p, p.length, demande, PORT));
                long fin = System.currentTimeMillis() + 2000;
                while (System.currentTimeMillis() < fin) {
                    try {
                        DatagramPacket r = new DatagramPacket(tampon, tampon.length);
                        s.receive(r);
                        lire(tampon, r.getLength(), r.getAddress().getHostAddress());
                    } catch (java.net.SocketTimeoutException t) { /* on continue d'écouter */ }
                }
            }
        } finally { s.close(); }
    }

    /** Racine ACN + PDU LLRP + PDU de demande de sondage, longueurs sur trois octets. */
    static byte[] demande(int tn, List<byte[]> connus) {
        int n = Math.min(connus.size(), 200);
        int sondage = 3 + 1 + 6 + 6 + 2 + 6 * n;
        int llrp = 3 + 4 + 16 + 4 + sondage;
        int racine = 3 + 4 + 16 + llrp;
        ByteBuffer b = ByteBuffer.allocate(16 + racine);
        b.putShort((short) 0x0010).putShort((short) 0x0000);
        b.put(new byte[] { 0x41, 0x53, 0x43, 0x2d, 0x45, 0x31, 0x2e, 0x31, 0x37, 0, 0, 0 });
        longueur(b, racine); b.putInt(0x0000000A); b.put(CID);           // VECTOR_ROOT_LLRP
        longueur(b, llrp); b.putInt(0x00000001); b.put(DIFFUSION_CID); b.putInt(tn);  // PROBE_REQUEST
        longueur(b, sondage); b.put((byte) 0x01);                         // PROBE_REQUEST_DATA
        b.put(new byte[] { 0, 0, 0, 0, 0, 0 });
        b.put(new byte[] { (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF });
        b.putShort((short) 0);                                            // aucun filtre
        for (int i = 0; i < n; i++) b.put(connus.get(i));
        return b.array();
    }

    private static void longueur(ByteBuffer b, int l) {
        b.put((byte) (0xF0 | ((l >> 16) & 0x0F))).put((byte) (l >> 8)).put((byte) l);
    }

    /** Lit une réponse de sondage ; tolère les longueurs sur deux ou trois octets. */
    void lire(byte[] b, int len, String ip) {
        if (len < 16 + 23) return;
        if (b[4] != 'A' || b[5] != 'S' || b[6] != 'C') return;
        int o = 16;
        int lr = (b[o] & 0x80) != 0 ? 3 : 2;
        if (ByteBuffer.wrap(b, o + lr, 4).getInt() != 0x0000000A) return;
        byte[] cid = new byte[16];
        System.arraycopy(b, o + lr + 4, cid, 0, 16);
        o += lr + 4 + 16;
        if (len < o + 3 + 4) return;
        int ll = (b[o] & 0x80) != 0 ? 3 : 2;
        if (ByteBuffer.wrap(b, o + ll, 4).getInt() != 0x00000002) return;   // PROBE_REPLY
        o += ll + 4 + 16 + 4;
        if (len < o + 3) return;
        int lp = (b[o] & 0x80) != 0 ? 3 : 2;
        o += lp;
        if (len < o + 1 + 6 + 6 + 1 || b[o] != 0x01) return;
        o++;
        byte[] uid = new byte[6];
        System.arraycopy(b, o, uid, 0, 6);
        Cible c = new Cible();
        c.uid = Rdm.uidTexte(uid);
        StringBuilder m = new StringBuilder();
        for (int i = 0; i < 6; i++) { if (i > 0) m.append(':'); m.append(String.format("%02X", b[o + 6 + i] & 255)); }
        c.mac = m.toString();
        int role = b[o + 12] & 255;
        c.role = role == 0 ? "appareil RDMnet" : role == 1 ? "contrôleur RDMnet"
               : role == 2 ? "broker" : "appareil LLRP seul";
        ByteBuffer cb = ByteBuffer.wrap(cid);
        c.cid = new UUID(cb.getLong(), cb.getLong()).toString();
        c.ip = ip;
        c.vu = System.currentTimeMillis();
        cibles.put(c.uid, c);
    }

    static byte[] uidOctets(String uid) {
        String h = uid.replace(":", "");
        byte[] u = new byte[6];
        for (int i = 0; i < 6; i++) u[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        return u;
    }

    private static byte[] octets(UUID u) {
        return ByteBuffer.allocate(16).putLong(u.getMostSignificantBits())
                .putLong(u.getLeastSignificantBits()).array();
    }
}
