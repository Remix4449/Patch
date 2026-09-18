package fr.regie.patch;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Mise à jour de l'application depuis la release « apk » du dépôt.
 *
 * La chaîne de montage y dépose l'APK et, à côté, une fiche `version.json` qui
 * porte le numéro de la version publiée. L'application lit la fiche, compare au
 * numéro qu'elle porte elle-même, télécharge si elle est en retard, et ouvre
 * l'écran d'installation du système.
 *
 * Ce dernier geste ne peut pas être automatisé : une application installée hors
 * magasin n'a pas le droit d'en installer une autre sans que l'utilisateur voie
 * l'écran d'Android et confirme. Tout le reste se fait tout seul.
 */
public class Maj {

    /** Où la chaîne de montage dépose l'APK et sa fiche de version. */
    public static final String RELEASE =
            "https://github.com/Remix4449/Patch/releases/download/apk/";

    private static final String APK = "patch-regie.apk";

    /**
     * repos, verification, ajour, disponible, telechargement, pret,
     * autorisation, erreur.
     */
    public volatile String phase = "repos";
    public volatile String erreur = "";

    public volatile int installe = 0;
    public volatile String nomInstalle = "";
    public volatile int publie = 0;
    public volatile String nomPublie = "";
    public volatile String sha = "";
    public volatile String branche = "";
    public volatile String date = "";
    public volatile long recus = 0;
    public volatile long taille = 0;

    private final MainActivity act;
    private volatile boolean occupe = false;

    public Maj(MainActivity a) {
        act = a;
        lireInstalle();
    }

    /** L'APK téléchargé, au même endroit pour l'application et le fournisseur. */
    public static File fichier(Context c) {
        if (c == null) return null;
        return new File(dossier(c), APK);
    }

    private static File dossier(Context c) {
        File d = new File(c.getFilesDir(), "maj");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** Numéro de la version déjà téléchargée, pour ne pas la reprendre. */
    private File marque() { return new File(dossier(act), "version"); }

    /* ------------------------------ démarrage --------------------------- */

    /**
     * Vérification au lancement de l'application. Différée : le Wi-Fi n'est pas
     * encore associé à la première seconde, et rien ne presse.
     */
    public void demarrer() {
        new Thread(new Runnable() {
            public void run() {
                try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
                verifier();
            }
        }, "maj-demarrage").start();
    }

    /** Vérifie, puis télécharge tout de suite si une version attend. */
    public void verifier() { lancer(true, true); }

    /** Reprend le seul téléchargement, après un échec réseau par exemple. */
    public void telecharger() { lancer(false, true); }

    private void lancer(final boolean verif, final boolean tele) {
        if (occupe) return;
        occupe = true;
        new Thread(new Runnable() {
            public void run() {
                try {
                    if (verif) verifierIci();
                    if (tele && "disponible".equals(phase)) telechargerIci();
                } finally {
                    occupe = false;
                }
            }
        }, "maj").start();
    }

    /* ----------------------------- vérification ------------------------- */

    private void verifierIci() {
        phase = "verification";
        erreur = "";
        HttpURLConnection c = null;
        try {
            lireInstalle();
            c = ouvrir(RELEASE + "version.json");
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("réponse " + code);
            JSONObject o = new JSONObject(texte(c.getInputStream()));
            publie = o.optInt("versionCode", 0);
            nomPublie = o.optString("versionName", "");
            sha = o.optString("sha", "");
            branche = o.optString("branche", "");
            date = o.optString("date", "");
            taille = o.optLong("octets", 0);
            if (publie <= installe) {
                oublier();                       // rien à installer : on ne garde rien
                phase = "ajour";
            } else if (dejaLa()) {
                recus = fichier(act).length();
                phase = "pret";
            } else {
                phase = "disponible";
            }
        } catch (Exception e) {
            erreur = dire(e);
            phase = "erreur";
        } finally {
            if (c != null) try { c.disconnect(); } catch (Exception ignore) { }
        }
    }

    private void lireInstalle() {
        try {
            PackageInfo p = act.getPackageManager().getPackageInfo(act.getPackageName(), 0);
            installe = p.versionCode;
            nomInstalle = p.versionName == null ? "" : p.versionName;
        } catch (Exception ignore) { }
    }

    /** Vrai si l'APK publié a déjà été rapatrié lors d'un lancement précédent. */
    private boolean dejaLa() {
        try {
            File f = fichier(act);
            if (f == null || !f.exists() || f.length() == 0) return false;
            String v = texte(new java.io.FileInputStream(marque())).trim();
            return Integer.parseInt(v) == publie;
        } catch (Exception e) { return false; }
    }

    private void oublier() {
        try { File f = fichier(act); if (f != null) f.delete(); } catch (Exception ignore) { }
        try { marque().delete(); } catch (Exception ignore) { }
    }

    /* ---------------------------- téléchargement ------------------------ */

    private void telechargerIci() {
        phase = "telechargement";
        recus = 0;
        erreur = "";
        HttpURLConnection c = null;
        InputStream in = null;
        OutputStream out = null;
        File part = new File(dossier(act), APK + ".part");
        try {
            c = ouvrir(RELEASE + APK);
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("réponse " + code);
            long annonce = c.getContentLength();
            if (annonce > 0) taille = annonce;
            in = c.getInputStream();
            out = new FileOutputStream(part);
            byte[] tampon = new byte[16384];
            int n;
            while ((n = in.read(tampon)) > 0) {
                out.write(tampon, 0, n);
                recus += n;
            }
            out.flush();
            out.close();
            out = null;
            File cible = fichier(act);
            cible.delete();
            if (!part.renameTo(cible)) throw new Exception("écriture impossible");
            ecrire(marque(), String.valueOf(publie));
            taille = cible.length();
            phase = "pret";
        } catch (Exception e) {
            erreur = dire(e);
            phase = "erreur";
            try { part.delete(); } catch (Exception ignore) { }
        } finally {
            fermer(in);
            fermer(out);
            if (c != null) try { c.disconnect(); } catch (Exception ignore) { }
        }
    }

    /* ---------------------------- installation -------------------------- */

    /**
     * Ouvre l'écran d'installation d'Android sur l'APK téléchargé.
     *
     * Deux préalables : l'autorisation « installer des applications inconnues »
     * pour cette application, demandée une seule fois ; et une adresse
     * `content://`, un `file://` étant refusé depuis Android 7.
     */
    public void installer() {
        final File f = fichier(act);
        if (f == null || !f.exists()) { verifier(); return; }
        act.runOnUiThread(new Runnable() {
            public void run() {
                try {
                    if (!act.getPackageManager().canRequestPackageInstalls()) {
                        phase = "autorisation";
                        act.demanderSourcesInconnues();
                        return;
                    }
                    Uri u = Uri.parse("content://" + FournisseurApk.AUTORITE + "/" + f.getName());
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(u, "application/vnd.android.package-archive");
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                             | Intent.FLAG_ACTIVITY_NEW_TASK);
                    act.startActivity(i);
                } catch (Exception e) {
                    erreur = dire(e);
                    phase = "erreur";
                }
            }
        });
    }

    /* -------------------------------- état ------------------------------ */

    public String etat() {
        try {
            JSONObject o = new JSONObject();
            o.put("phase", phase);
            o.put("erreur", erreur);
            o.put("installe", installe);
            o.put("nomInstalle", nomInstalle);
            o.put("publie", publie);
            o.put("nomPublie", nomPublie);
            o.put("sha", sha);
            o.put("branche", branche);
            o.put("date", date);
            o.put("recus", recus);
            o.put("taille", taille);
            return o.toString();
        } catch (Exception e) { return "{\"phase\":\"erreur\"}"; }
    }

    /* ----------------------------- utilitaires -------------------------- */

    /**
     * Ouvre la connexion sur un réseau qui a vraiment internet.
     *
     * `Reseau.java` épingle le processus sur le Wi-Fi du plateau, qui n'a
     * souvent aucun accès extérieur : sans ce détour, la vérification échouerait
     * là où elle sert le plus, alors que la 4G est disponible à côté.
     */
    private HttpURLConnection ouvrir(String adresse) throws Exception {
        URL u = new URL(adresse);
        Network n = reseauInternet();
        HttpURLConnection c = (HttpURLConnection)
                (n != null ? n.openConnection(u) : u.openConnection());
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        c.setUseCaches(false);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Cache-Control", "no-cache");
        c.setRequestProperty("Accept", "*/*");
        c.setRequestProperty("User-Agent", "patch-regie");
        return c;
    }

    private Network reseauInternet() {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    act.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c == null) continue;
                if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return n;
            }
        } catch (Exception ignore) { }
        return null;
    }

    private static String texte(InputStream in) throws Exception {
        try {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] t = new byte[4096];
            int n;
            while ((n = in.read(t)) > 0) b.write(t, 0, n);
            return new String(b.toByteArray(), "UTF-8");
        } finally { fermer(in); }
    }

    private static void ecrire(File f, String s) throws Exception {
        FileOutputStream o = new FileOutputStream(f);
        try { o.write(s.getBytes("UTF-8")); } finally { o.close(); }
    }

    private static void fermer(java.io.Closeable c) {
        if (c != null) try { c.close(); } catch (Exception ignore) { }
    }

    /** Message court et lisible : l'écran n'a pas la place d'une trace Java. */
    private static String dire(Exception e) {
        if (e instanceof java.net.UnknownHostException) return "pas d'accès à internet";
        if (e instanceof java.net.SocketTimeoutException) return "délai dépassé";
        String m = e.getMessage();
        return (m == null || m.isEmpty()) ? e.getClass().getSimpleName() : m;
    }
}
