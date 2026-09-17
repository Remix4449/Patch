package fr.regie.patch;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Émission continue : la télécommande pose des univers, ce fil les répète.
 *
 * Un gradateur ne garde pas un niveau reçu une seule fois — les récepteurs
 * sACN relâchent l'univers après quelques secondes de silence, et un nœud
 * Art-Net fait de même. La télécommande doit donc tenir le flux tant qu'elle
 * affiche un niveau : 30 trames par seconde, ce que fait n'importe quel
 * pupitre.
 */
public class Emetteur {

    private static final int PERIODE = 33;          // ms, soit ~30 Hz

    private final ArtNet art;
    private final Sacn sacn;

    /** Univers (base 1) → 512 niveaux. Remplacée d'un bloc par l'interface. */
    private final Map<Integer, byte[]> trames = new ConcurrentHashMap<Integer, byte[]>();

    public volatile String proto = "sACN";
    public volatile int prio = 100;
    public volatile String cible = "";
    public volatile boolean actif;
    public volatile long envois;

    private Thread boucle;

    public Emetteur(ArtNet a, Sacn s) { art = a; sacn = s; }

    public synchronized void regler(String protocole, int priorite, String destination) {
        proto = (protocole != null && protocole.toLowerCase().startsWith("a")) ? "Art-Net" : "sACN";
        prio = Math.max(0, Math.min(200, priorite <= 0 ? 100 : priorite));
        cible = destination == null ? "" : destination.trim();
    }

    public synchronized void demarrer() {
        if (actif) return;
        actif = true;
        boucle = new Thread(new Runnable() { public void run() { tourner(); } }, "emission");
        boucle.setDaemon(true);
        boucle.start();
    }

    /**
     * Remplace l'ensemble des univers émis. Ceux qui disparaissent sont
     * relâchés proprement, sinon les projecteurs resteraient allumés sur la
     * dernière valeur reçue.
     */
    public void poser(Map<Integer, byte[]> nouvelles) {
        for (Integer u : new HashMap<Integer, byte[]>(trames).keySet())
            if (!nouvelles.containsKey(u)) { relacher(u); trames.remove(u); }
        for (Map.Entry<Integer, byte[]> e : nouvelles.entrySet())
            trames.put(e.getKey(), e.getValue());
        if (!nouvelles.isEmpty()) demarrer();
    }

    /** Relâche tous les univers et arrête le fil. */
    public synchronized void arreter() {
        for (Integer u : new HashMap<Integer, byte[]>(trames).keySet()) relacher(u);
        trames.clear();
        actif = false;
    }

    /** Trois trames à zéro, marquées fin de flux en sACN : le plateau s'éteint. */
    private void relacher(int u) {
        byte[] zero = new byte[512];
        for (int i = 0; i < 3; i++) {
            if ("Art-Net".equals(proto)) art.emettre(u - 1, zero, cible);
            else sacn.emettre(u, zero, prio, cible, true);
            try { Thread.sleep(8); } catch (InterruptedException e) { return; }
        }
    }

    private void tourner() {
        while (actif) {
            long t = System.currentTimeMillis();
            for (Map.Entry<Integer, byte[]> e : trames.entrySet()) {
                if ("Art-Net".equals(proto)) art.emettre(e.getKey() - 1, e.getValue(), cible);
                else sacn.emettre(e.getKey(), e.getValue(), prio, cible, false);
                envois++;
            }
            long reste = PERIODE - (System.currentTimeMillis() - t);
            try { Thread.sleep(Math.max(5, reste)); } catch (InterruptedException x) { return; }
        }
    }
}
