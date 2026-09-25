package fr.regie.patch;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.provider.Settings;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** Coque de l'application : une WebView plein écran et le pont réseau. */
public class MainActivity extends Activity {

    private static final int CODE_GDTF = 4242;
    private static final int CODE_SOURCES = 4243;

    private WebView web;
    private WebView impression;          // gardée en vie le temps de l'impression
    private Regie pont;

    /** Mise à jour de l'application, partagée avec le pont. */
    public Maj maj;

    /** Résultat de la dernière lecture GDTF, relu par le pont. */
    public volatile String gdtfJson = "{}";

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

    @Override
    protected void onActivityResult(int requete, int resultat, Intent data) {
        super.onActivityResult(requete, resultat, data);
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
