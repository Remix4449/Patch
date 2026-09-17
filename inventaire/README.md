# Inventaire régie — canevas

Trois propositions d'interface pour l'inventaire matériel du plateau, à partir des bases Notion
existantes (Projecteurs, Machinerie, Nacelle et tour, Hauteurs de Passerelles & Plateformes
Élévatrices, MDG).

| Fichier | Canevas | Parti pris |
| --- | --- | --- |
| `canevas-a-regie.html` | Régie Noire | Console sombre, rail + liste dense + fiche latérale |
| `canevas-b-atelier.html` | Atelier Plateau | Manuel d'exploitation clair, onglets, fiches pleine page, imprimable |
| `canevas-c-patch.html` | Patch Poche | Mobile d'abord, tuiles colorées, barre d'onglets basse |
| `index.html` | — | Page de comparaison |
| `common.js` | — | Données + calculs partagés par les trois |

Les trois canevas couvrent le même périmètre : inventaire (projecteurs, machinerie, hauteurs, MDG)
et cinq outils (calcul DMX, gélatines Lee → RGBWA, flux NDI, réseau/ping, Art-Net/sACN).

## Données

`common.js` contient un extrait figé des bases Notion, pour que les maquettes soient lisibles hors
ligne. En production, cette couche est remplacée par une synchronisation Notion (API + cache local),
ou par une reprise complète des données dans l'application.

Les modes DMX ne sont pas présents dans la base Notion actuelle : le champ est prévu dans l'interface
et affiché comme « à renseigner ». Seul le Robe LEDBeam 150 porte un exemple, marqué `démo`.

Les teintes Lee sont des **approximations écran**, destinées à donner un point de départ de mélange
RGBWA. Elles ne remplacent pas un relevé au colorimètre sur le moteur LED concerné.

## Calculs réels (pas de maquette)

- `patch(u, ch, n, empreinte)` — adressage séquentiel sans chevauchement d'univers
- `artnet(u)` — Port-Address : net (0-127) / subnet (0-15) / universe (0-15)
- `sacn(u)` — univers 1-63999 et adresse multicast `239.255.<hi>.<lo>`
- `toRGBWA(hex)` — blanc = composante commune RGB, ambre extrait sur l'axe (255, 126, 0)

## Ce qui demande un agent local

Le navigateur n'a accès ni à l'ICMP, ni aux sockets UDP bruts, ni au multicast. Ces trois outils sont
donc affichés en **démonstration** et nécessitent un service tournant sur le poste de régie :

| Outil | Besoin technique |
| --- | --- |
| Ping / découverte réseau | ICMP, ARP, scan de sous-réseau |
| Flux NDI | NDI SDK ou découverte mDNS |
| Art-Net / sACN | UDP 6454 et 5568, multicast 239.255.x.x |

Deux pistes : un petit service Node ou Python sur le poste, interrogé par l'interface web (WebSocket) ;
ou une application de bureau Tauri/Electron qui embarque interface et agent. Le reste de
l'application fonctionne en web pur, hors ligne compris.
