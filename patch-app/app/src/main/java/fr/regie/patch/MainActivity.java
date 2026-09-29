package fr.regie.patch;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.provider.CalendarContract;
import android.provider.Settings;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Coque de l'application : une WebView plein écran et le pont réseau. */
public class MainActivity extends Activity {

    private static final int CODE_GDTF = 4242;
    private static final int CODE_SOURCES = 4243;
    private static final int CODE_OUVRIR = 4244;
    private static final int CODE_ENREGISTRER = 4245;
    private static final int CODE_AGENDA = 4246;

    private WebView web;
    private WebView impression;          // gardée en vie le temps de l'impression
    private Regie pont;

    /** Mise à jour de l'application, partagée avec le pont. */
    public Maj maj;

    /** Résultat de la dernière lecture GDTF, relu par le pont. */
    public volatile String gdtfJson = "{}";

    /** Échange de fichiers texte (logistique) : état relu par le pont. */
    public volatile String fichierJson = "{}";
    private volatile String aEcrire = null;

    /** Autorisation de lire l'agenda : « oui », « non » ou « attente ». */
    public volatile String agendaPerm = "";

    @Override
    protected void onCreate(Bundle etat) {
        super.onCreate(etat);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());   // sans quoi confirm() est ignoré
        WebView.setWebContentsDebuggingEnabled(true);

        maj = new Maj(this);
        pont = new Regie(this);
        web.addJavascriptInterface(pont, "Regie");
        web.loadUrl("file:///android_asset/www/index.html");
        setContentView(web);
    }

    /**
     * Le geste de retour d'Android fermait l'application depuis n'importe quel
     * écran, y compris en plein patch : la page n'empilait rien et la coque ne
     * surchargeait rien. La page pose désormais une entrée par écran ; on rend
     * la main à Android seulement quand il n'en reste plus.
     */
    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    /** Ouvre la boîte d'impression du système, qui sait enregistrer en PDF. */
    public void imprimerHtml(final String html, final String nom) {
        runOnUiThread(new Runnable() {
            public void run() {
                final WebView w = new WebView(MainActivity.this);
                w.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView v, String url) {
                        PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                        if (pm == null) return;
                        PrintDocumentAdapter ad = v.createPrintDocumentAdapter(nom);
                        pm.print(nom, ad, new PrintAttributes.Builder()
                                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                                .build());
                    }
                });
                impression = w;
                w.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
            }
        });
    }

    /**
     * Écran système « autoriser cette application à installer des applications ».
     * Android l'exige une fois par application ; au retour, on reprend
     * l'installation là où elle s'était arrêtée.
     */
    public void demanderSourcesInconnues() {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(i, CODE_SOURCES);
        } catch (Exception e) {
            maj.erreur = "écran des autorisations introuvable";
            maj.phase = "erreur";
        }
    }

    /** Sélecteur de fichier pour un GDTF. */
    public void choisirGdtf() {
        gdtfJson = "{}";
        runOnUiThread(new Runnable() {
            public void run() {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try { startActivityForResult(i, CODE_GDTF); }
                catch (Exception e) { gdtfJson = "{\"erreur\":\"aucun explorateur de fichiers\"}"; }
            }
        });
    }

    /**
     * Fichiers texte de la logistique : ouvrir un export (JSON, CSV, iCal)
     * venu d'une autre application, ou enregistrer le nôtre où l'on veut —
     * Téléchargements, Drive, un dossier synchronisé avec le PC.
     */
    public void ouvrirFichier() {
        fichierJson = "{}";
        runOnUiThread(new Runnable() {
            public void run() {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try { startActivityForResult(i, CODE_OUVRIR); }
                catch (Exception e) { fichierJson = erreurJson("aucun explorateur de fichiers"); }
            }
        });
    }

    public void enregistrerFichier(final String nom, final String mime, String contenu) {
        fichierJson = "{}";
        aEcrire = contenu;
        runOnUiThread(new Runnable() {
            public void run() {
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(mime);
                i.putExtra(Intent.EXTRA_TITLE, nom);
                try { startActivityForResult(i, CODE_ENREGISTRER); }
                catch (Exception e) { fichierJson = erreurJson("aucun explorateur de fichiers"); }
            }
        });
    }

    /** Ouvre l'agenda du téléphone sur un rendez-vous prérempli : aucune permission. */
    public boolean ajouterAgenda(String titre, String lieu, String note, long debut, long fin, boolean journee) {
        try {
            Intent i = new Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.Events.TITLE, titre)
                    .putExtra(CalendarContract.Events.EVENT_LOCATION, lieu)
                    .putExtra(CalendarContract.Events.DESCRIPTION, note)
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, debut)
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, fin)
                    .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, journee);
            startActivity(i);
            return true;
        } catch (Exception e) { return false; }
    }

    public boolean agendaAutorise() {
        return checkSelfPermission(android.Manifest.permission.READ_CALENDAR)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    public void demanderAgenda() {
        if (agendaAutorise()) { agendaPerm = "oui"; return; }
        agendaPerm = "attente";
        runOnUiThread(new Runnable() {
            public void run() {
                requestPermissions(new String[] { android.Manifest.permission.READ_CALENDAR }, CODE_AGENDA);
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requete, String[] perms, int[] res) {
        super.onRequestPermissionsResult(requete, perms, res);
        if (requete == CODE_AGENDA) agendaPerm = agendaAutorise() ? "oui" : "non";
    }

    private static String erreurJson(String m) {
        try { return new JSONObject().put("erreur", m).toString(); }
        catch (Exception e) { return "{\"erreur\":\"erreur\"}"; }
    }

    private void lireTexte(final Uri uri) {
        new Thread(new Runnable() {
            public void run() {
                InputStream in = null;
                try {
                    in = getContentResolver().openInputStream(uri);
                    if (in == null) { fichierJson = erreurJson("fichier illisible"); return; }
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        if (out.size() > 5 * 1024 * 1024) { fichierJson = erreurJson("fichier trop gros"); return; }
                    }
                    fichierJson = new JSONObject().put("pret", true)
                            .put("texte", new String(out.toByteArray(), "UTF-8")).toString();
                } catch (Exception e) {
                    fichierJson = erreurJson("lecture impossible");
                } finally {
                    try { if (in != null) in.close(); } catch (Exception ignore) { }
                }
            }
        }, "fichier-lire").start();
    }

    private void ecrireTexte(final Uri uri, final String contenu) {
        new Thread(new Runnable() {
            public void run() {
                OutputStream out = null;
                try {
                    out = getContentResolver().openOutputStream(uri, "wt");
                    if (out == null) { fichierJson = erreurJson("écriture impossible"); return; }
                    out.write(contenu.getBytes("UTF-8"));
                    out.flush();
                    fichierJson = "{\"pret\":true}";
                } catch (Exception e) {
                    fichierJson = erreurJson("écriture impossible");
                } finally {
                    try { if (out != null) out.close(); } catch (Exception ignore) { }
                }
            }
        }, "fichier-ecrire").start();
    }

    @Override
    protected void onActivityResult(int requete, int resultat, Intent data) {
        super.onActivityResult(requete, resultat, data);
        if (requete == CODE_OUVRIR || requete == CODE_ENREGISTRER) {
            if (resultat != RESULT_OK || data == null || data.getData() == null) {
                aEcrire = null;
                fichierJson = erreurJson("annulé");
                return;
            }
            if (requete == CODE_OUVRIR) lireTexte(data.getData());
            else {
                String c = aEcrire;
                aEcrire = null;
                ecrireTexte(data.getData(), c == null ? "" : c);
            }
            return;
        }
        if (requete == CODE_SOURCES) {
            if (getPackageManager().canRequestPackageInstalls()) maj.installer();
            else maj.phase = "pret";           // refusé : l'écran le redemandera
            return;
        }
        if (requete != CODE_GDTF) return;
        if (resultat != RESULT_OK || data == null || data.getData() == null) {
            gdtfJson = "{\"erreur\":\"import annulé\"}";
            return;
        }
        final Uri uri = data.getData();
        new Thread(new Runnable() {
            public void run() { gdtfJson = Gdtf.lire(getContentResolver(), uri); }
        }, "gdtf").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (maj != null) maj.reprendre();
    }

    @Override
    protected void onDestroy() {
        if (pont != null) pont.stopAll();
        super.onDestroy();
    }
}
