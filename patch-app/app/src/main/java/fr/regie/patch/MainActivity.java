package fr.regie.patch;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** Coque de l'application : une WebView plein écran et le pont réseau. */
public class MainActivity extends Activity {

    private WebView web;
    private Regie pont;

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
        if (android.os.Build.VERSION.SDK_INT >= 19) WebView.setWebContentsDebuggingEnabled(true);

        pont = new Regie(this);
        web.addJavascriptInterface(pont, "Regie");
        web.loadUrl("file:///android_asset/www/index.html");
        setContentView(web);
    }

    @Override
    protected void onDestroy() {
        if (pont != null) pont.stopAll();
        super.onDestroy();
    }
}
