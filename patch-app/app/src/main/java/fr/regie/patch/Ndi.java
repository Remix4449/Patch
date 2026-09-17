package fr.regie.patch;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Découverte des sources NDI par mDNS (_ndi._tcp). Donne le nom, la machine,
 * l'adresse et le port. La vignette en direct demanderait le SDK NDI.
 *
 * NsdManager ne supporte qu'une résolution à la fois : elles sont mises en file.
 */
public class Ndi {

    public static class Source {
        public String nom = "", machine = "", ip = "";
        public int port;
    }

    public final List<Source> sources = new CopyOnWriteArrayList<Source>();
    public volatile boolean encours;

    private final Context ctx;
    private NsdManager nsd;
    private NsdManager.DiscoveryListener chercheur;
    private final ConcurrentLinkedQueue<NsdServiceInfo> file = new ConcurrentLinkedQueue<NsdServiceInfo>();
    private final AtomicBoolean occupe = new AtomicBoolean(false);

    public Ndi(Context c) { ctx = c.getApplicationContext(); }

    public void lancer() {
        if (encours) return;
        try {
            nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
            if (nsd == null) return;
            chercheur = new NsdManager.DiscoveryListener() {
                public void onDiscoveryStarted(String t) { encours = true; }
                public void onDiscoveryStopped(String t) { encours = false; }
                public void onStartDiscoveryFailed(String t, int e) { encours = false; }
                public void onStopDiscoveryFailed(String t, int e) { encours = false; }
                public void onServiceFound(NsdServiceInfo info) { file.add(info); suivant(); }
                public void onServiceLost(NsdServiceInfo info) {
                    for (Source s : sources)
                        if (s.nom.equals(info.getServiceName())) sources.remove(s);
                }
            };
            nsd.discoverServices("_ndi._tcp.", NsdManager.PROTOCOL_DNS_SD, chercheur);
        } catch (Exception e) { encours = false; }
    }

    private void suivant() {
        if (!occupe.compareAndSet(false, true)) return;
        final NsdServiceInfo info = file.poll();
        if (info == null) { occupe.set(false); return; }
        try {
            nsd.resolveService(info, new NsdManager.ResolveListener() {
                public void onResolveFailed(NsdServiceInfo i, int e) { occupe.set(false); suivant(); }
                public void onServiceResolved(NsdServiceInfo i) {
                    Source s = new Source();
                    s.nom = i.getServiceName();
                    s.port = i.getPort();
                    if (i.getHost() != null) {
                        s.ip = i.getHost().getHostAddress();
                        s.machine = i.getHost().getHostName();
                    }
                    int sep = s.nom.indexOf(" (");
                    if (sep > 0) {
                        s.machine = s.nom.substring(0, sep);
                        s.nom = s.nom.substring(sep + 2).replace(")", "");
                    }
                    boolean deja = false;
                    for (Source x : sources) if (x.nom.equals(s.nom) && x.ip.equals(s.ip)) deja = true;
                    if (!deja) sources.add(s);
                    occupe.set(false);
                    suivant();
                }
            });
        } catch (Exception e) { occupe.set(false); }
    }

    public void arreter() {
        try { if (nsd != null && chercheur != null) nsd.stopServiceDiscovery(chercheur); }
        catch (Exception ignore) { }
        encours = false;
        sources.clear();
    }
}
