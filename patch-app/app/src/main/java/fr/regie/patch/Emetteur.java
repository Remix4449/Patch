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
    public volatile long envois, echecs;

    private volatile Thread boucle;

    public Emetteur(ArtNet a, Sacn s) { art = a; sacn = s; }

    public synchronized void regler(String protocole, int priorite, String destination) {
        String p = (protocole != null && protocole.toLowerCase().startsWith("a")) ? "Art-Net" : "sACN";
        String c = destination == null ? "" : destination.trim();
        // Changer de protocole ou de destination en cours d'émission : l'ancien
        // flux est clos proprement, sinon le nœud garde deux sources qui se
        // disputent l'univers jusqu'à ce que la première expire.
        // On bascule d'abord, pour que le fil d'émission n'intercale plus de
        // trame sur l'ancienne route entre les trames de fin.
        String avantProto = proto, avantCible = cible;
        proto = p;
        prio = Math.max(0, Math.min(200, priorite <= 0 ? 100 : priorite));
        cible = c;
        if (actif && (!p.equals(avantProto) || !c.equals(avantCible)))
            for (Integer u : trames.keySet()) relacher(u, avantProto, avantCible);
    }

    public synchronized void demarrer() {
        // Un arrêt suivi aussitôt d'un départ laissait l'ancien fil reprendre à
        // son réveil : deux fils émettaient alors les mêmes univers, avec des
        // numéros de séquence entremêlés que les nœuds rejettent par paquets.
        if (actif && boucle != null && boucle.isAlive()) return;
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
    public synchronized void poser(Map<Integer, byte[]> nouvelles) {
        // Retirer l'univers avant de le relâcher : le fil d'émission ne doit plus
        // intercaler l'ancienne trame entre les trames à zéro.
        for (Integer u : new HashMap<Integer, byte[]>(trames).keySet())
            if (!nouvelles.containsKey(u)) { trames.remove(u); relacher(u, proto, cible); }
        for (Map.Entry<Integer, byte[]> e : nouvelles.entrySet())
            trames.put(e.getKey(), e.getValue());
        if (!nouvelles.isEmpty()) demarrer();
    }

    /** Relâche tous les univers et arrête le fil. */
    public synchronized void arreter() {
        actif = false;
        Thread b = boucle;
        if (b != null) b.interrupt();
        java.util.Set<Integer> us = new java.util.HashSet<Integer>(trames.keySet());
        trames.clear();
        for (Integer u : us) relacher(u, proto, cible);
    }

    /** Trois trames à zéro, marquées fin de flux en sACN : le plateau s'éteint. */
    private void relacher(int u, String proto, String cible) {
        byte[] zero = new byte[512];
        for (int i = 0; i < 3; i++) {
            if ("Art-Net".equals(proto)) art.emettre(u - 1, zero, cible);
            else sacn.emettre(u, zero, prio, cible, true);
            try { Thread.sleep(8); } catch (InterruptedException e) { return; }
        }
    }

    private void tourner() {
        while (actif && boucle == Thread.currentThread()) {
            long t = System.currentTimeMillis();
            for (Map.Entry<Integer, byte[]> e : trames.entrySet()) {
                boolean ok = "Art-Net".equals(proto)
                        ? art.emettre(e.getKey() - 1, e.getValue(), cible)
                        : sacn.emettre(e.getKey(), e.getValue(), prio, cible, false);
                // Compter les départs réussis, pas les tentatives : un compteur qui
                // monte pendant que rien ne part ne dit rien à personne.
                if (ok) envois++; else echecs++;
            }
            long reste = PERIODE - (System.currentTimeMillis() - t);
            try { Thread.sleep(Math.max(5, reste)); } catch (InterruptedException x) { return; }
        }
    }
}
