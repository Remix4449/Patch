package fr.regie.patch;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Réception d'une source NDI, sans le SDK : connexion TCP au port annoncé en
 * mDNS, demande du flux vidéo basse qualité (l'aperçu que tout émetteur NDI
 * fabrique, 640 pixels de large environ), puis décodage SpeedHQ en Java.
 *
 * Protocole relevé en observant l'émetteur et le récepteur du SDK NDI 6 :
 *  - chaque message commence par 12 octets : type (u16, 0x8000 | genre),
 *    version (u16), taille de l'en-tête (u32), taille des données (u32) ;
 *  - genre 1 : texte XML ; en-tête (8 octets nuls) et texte sont brouillés
 *    ensemble par {@link #brouiller}, graine = leur taille totale ;
 *  - genre 3 : image, pour un récepteur qui s'annonce en version vidéo 3 ;
 *    seul l'en-tête est brouillé (même graine : en-tête + données). Il porte
 *    le FourCC, la taille, la cadence ; les données sont l'image SpeedHQ brute.
 * Les versions vidéo 4 et plus brouillent l'en-tête autrement : on s'annonce en
 * version 3, que les émetteurs récents servent toujours.
 *
 * Le fil de réception lit tout ce qui arrive ; le décodage ne prend que la
 * dernière image quand il est libre, pour ne jamais prendre de retard.
 */
public class NdiFlux {

    public volatile String etat = "arrêt";     // arrêt, connexion, attente, direct, erreur
    public volatile String erreur = "";
    public volatile String fourcc = "";
    public volatile int largeur, hauteur;
    public volatile double aspect;             // rapport largeur / hauteur de l'image affichée
    public volatile double cadence;            // images par seconde annoncées par la source
    public volatile double affichees;          // images décodées par seconde
    public volatile long octets;               // reçus depuis la connexion
    public volatile double mbps;               // débit reçu, en Mbit/s
    public volatile long debut;
    public volatile String source = "";

    private static final long PERIODE = 80;    // ms au moins entre deux décodages

    private volatile Socket prise;
    private volatile Thread fil, decodeur;
    private final Object verrou = new Object();

    // Dernière image reçue, en attente de décodage.
    private byte[] enAttente;
    private String fAttente;
    private int lAttente, hAttente;

    // Dernière image décodée, copiée pour l'affichage.
    private int[] image;
    private int lImage, hImage;
    private long numero, numeroLu = -1;

    public synchronized void ouvrir(final String ip, final int port, String nom) {
        fermer();
        source = nom == null ? "" : nom;
        etat = "connexion";
        erreur = "";
        fourcc = "";
        largeur = hauteur = 0;
        cadence = affichees = aspect = mbps = 0;
        octets = 0;
        debut = System.currentTimeMillis();
        synchronized (verrou) { enAttente = null; image = null; numero = 0; numeroLu = -1; }
        final Thread t = new Thread(new Runnable() {
            public void run() { recevoir(ip, port, Thread.currentThread()); }
        }, "ndi-flux");
        final Thread d = new Thread(new Runnable() {
            public void run() { decoder(Thread.currentThread()); }
        }, "ndi-decodage");
        fil = t;
        decodeur = d;
        t.start();
        d.start();
    }

    public synchronized void fermer() {
        Thread t = fil, d = decodeur;
        fil = null;
        decodeur = null;
        Socket s = prise;
        prise = null;
        if (s != null) try { s.close(); } catch (IOException ignore) { }
        if (t != null) t.interrupt();
        if (d != null) d.interrupt();
        synchronized (verrou) { verrou.notifyAll(); }
        etat = "arrêt";
    }

    /** Dernière image décodée si elle est nouvelle, sinon null. Tableau {pixels ARGB, largeur, hauteur}. */
    public Object[] nouvelleImage() {
        synchronized (verrou) {
            if (image == null || numero == numeroLu) return null;
            numeroLu = numero;
            return new Object[] { image.clone(), lImage, hImage };
        }
    }

    /* ------------------------------ réception ------------------------------ */

    private void recevoir(String ip, int port, Thread moi) {
        Socket s = new Socket();
        try {
            prise = s;
            s.setTcpNoDelay(true);
            s.setReceiveBufferSize(1 << 20);
            s.connect(new InetSocketAddress(ip, port), 4000);
            s.setSoTimeout(8000);
            OutputStream o = s.getOutputStream();
            o.write(texte("<ndi_version text=\"3\" video=\"3\" audio=\"3\" sdk=\"3.5.1\" platform=\"ANDROID\"/>"));
            o.write(texte("<ndi_identify name=\"PATCH (Flux)\"/>"));
            o.write(texte("<ndi_video quality=\"low\"/>"));
            o.write(texte("<ndi_enabled_streams video=\"true\" audio=\"false\" text=\"false\""
                    + " shq_skip_block=\"false\" shq_short_dc=\"false\"/>"));
            o.flush();
            if (fil == moi) etat = "attente";

            DataInputStream in = new DataInputStream(s.getInputStream());
            byte[] tete = new byte[12];
            while (fil == moi) {
                in.readFully(tete);
                int type = u16(tete, 0);
                long th = u32(tete, 4), td = u32(tete, 8);
                if ((type & 0x8000) == 0 || th > 1024 || td > 64L << 20)
                    throw new IOException("flux NDI incompris");
                byte[] h = new byte[(int) th];
                in.readFully(h);
                octets += 12 + th + td;
                if (type == 0x8003 && th >= 24) {
                    brouiller(h, h.length, (int) (th + td), false);
                    byte[] d = new byte[(int) td];
                    in.readFully(d);
                    String f = new String(h, 0, 4, StandardCharsets.ISO_8859_1);
                    int l = (int) u32(h, 4), ht = (int) u32(h, 8);
                    long n = u32(h, 12), dd = u32(h, 16);
                    float a = Float.intBitsToFloat((int) u32(h, 20));
                    // Une trame seule (entrelacé) garde l'aspect de l'image entière.
                    aspect = a > 0.2f && a < 10f ? a : (ht > 0 ? (double) l / ht : 0);
                    if (dd > 0) cadence = Math.round(n * 100.0 / dd) / 100.0;
                    fourcc = f;
                    largeur = l;
                    hauteur = ht;
                    if (!f.startsWith("SHQ")) {
                        erreur = "format " + f.trim() + " non pris en charge (source NDI|HX ?)";
                        etat = "erreur";
                        continue;
                    }
                    if (fil == moi && !"erreur".equals(etat)) etat = "direct";
                    synchronized (verrou) {
                        enAttente = d; fAttente = f; lAttente = l; hAttente = ht;
                        verrou.notifyAll();
                    }
                } else {
                    in.skipBytes((int) td);    // texte, son, métadonnées : pas utiles ici
                }
            }
        } catch (Exception e) {
            if (fil == moi) {
                erreur = raison(e);
                etat = "erreur";
            }
        } finally {
            try { s.close(); } catch (IOException ignore) { }
        }
    }

    private static String raison(Exception e) {
        if (e instanceof java.net.SocketTimeoutException) return "la source n'envoie plus d'image";
        if (e instanceof java.net.ConnectException) return "connexion refusée par la source";
        if (e instanceof EOFException) return "la source a coupé la connexion";
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }

    private void decoder(Thread moi) {
        SpeedHq shq = new SpeedHq();
        long t0 = System.currentTimeMillis(), o0 = 0;
        int n = 0;
        while (decodeur == moi) {
            long depart = System.currentTimeMillis();
            byte[] d; String f; int l, h;
            synchronized (verrou) {
                while (enAttente == null && decodeur == moi) {
                    try { verrou.wait(500); } catch (InterruptedException e) { return; }
                }
                if (decodeur != moi) return;
                d = enAttente; f = fAttente; l = lAttente; h = hAttente;
                enAttente = null;
            }
            try {
                shq.regler(f, l, h);
                int[] px = shq.decoder(d, 0, d.length);
                synchronized (verrou) {
                    if (image == null || image.length != px.length) image = new int[px.length];
                    System.arraycopy(px, 0, image, 0, px.length);
                    lImage = l; hImage = h;
                    numero++;
                }
                n++;
            } catch (SpeedHq.Erreur e) {
                erreur = "image illisible : " + e.getMessage();
            }
            long t = System.currentTimeMillis();
            if (t - t0 >= 1000) {
                affichees = Math.round(n * 10000.0 / (t - t0)) / 10.0;
                long o = octets;
                mbps = Math.round((o - o0) * 8.0 / (t - t0) / 100.0) / 10.0;
                o0 = o;
                n = 0;
                t0 = t;
            }
            // Une douzaine d'images par seconde suffit à l'aperçu et ménage la batterie.
            long reste = PERIODE - (System.currentTimeMillis() - depart);
            if (reste > 0) try { Thread.sleep(reste); } catch (InterruptedException e) { return; }
        }
    }

    /* ------------------------------ messages ------------------------------ */

    static byte[] texte(String xml) {
        byte[] x = xml.getBytes(StandardCharsets.UTF_8);
        int n = 8 + x.length + 1;                // en-tête nul, texte, zéro final
        byte[] m = new byte[12 + n];
        m[0] = 0x01; m[1] = (byte) 0x80;         // 0x8001 : texte
        m[2] = 0x02;                             // version 2
        m[4] = 8;                                // taille de l'en-tête
        int td = x.length + 1;
        m[8] = (byte) td; m[9] = (byte) (td >> 8); m[10] = (byte) (td >> 16); m[11] = (byte) (td >>> 24);
        byte[] corps = new byte[n];
        System.arraycopy(x, 0, corps, 8, x.length);
        brouiller(corps, n, n, true);
        System.arraycopy(corps, 0, m, 12, n);
        return m;
    }

    /**
     * Brouillage NDI des messages de contrôle et des en-têtes d'image : un
     * générateur xorshift amorcé par la graine, chaîné sur le texte en clair.
     * Réversible : chiffrer = true brouille, false rétablit. Les derniers
     * octets (moins de 8) prennent un tour de plus du générateur.
     */
    static void brouiller(byte[] b, int n, int graine, boolean chiffrer) {
        long g = ((long) graine << 32) | (graine & 0xffffffffL);
        long s1 = g ^ 0xb711674bd24f4b24L, s2 = g ^ 0xb080d84f1fe3bf44L, t = s1;
        for (int i = 0; i < n; i += 8) {
            int k = Math.min(8, n - i);
            long c = 0;
            for (int j = 0; j < k; j++) c |= (b[i + j] & 0xffL) << (8 * j);
            s1 = s2;
            t ^= t << 23;
            t = ((s1 >>> 9 ^ t) >>> 17) ^ t ^ s1;
            long cle = t + s1;
            long clair = chiffrer ? c : c ^ cle;
            long sortie = c ^ cle;
            s2 = t ^ clair;
            for (int j = 0; j < k; j++) b[i + j] = (byte) (sortie >>> (8 * j));
            t = s1;
        }
    }

    private static int u16(byte[] b, int i) { return (b[i] & 0xff) | ((b[i + 1] & 0xff) << 8); }

    private static long u32(byte[] b, int i) {
        return (b[i] & 0xffL) | ((b[i + 1] & 0xffL) << 8) | ((b[i + 2] & 0xffL) << 16) | ((b[i + 3] & 0xffL) << 24);
    }
}
