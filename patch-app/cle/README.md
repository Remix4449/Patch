# La clé de signature

`patch.jks` signe l'APK. Elle est dans le dépôt exprès, et c'est le seul moyen
d'avoir des mises à jour qui s'installent.

Android refuse d'installer une mise à jour signée par une autre clé que la
version déjà en place : il propose alors de désinstaller d'abord, ce qui efface
le patch et la feuille de gradateurs. Or une construction sans clé fixe en
fabrique une nouvelle à chaque fois, sur une machine neuve à chaque fois — deux
APK de suite ne s'enchaînent donc jamais. Avec cette clé, ils s'enchaînent.

- fichier : `patch.jks`, format PKCS#12
- alias : `patch`
- mot de passe du magasin et de la clé : `patch-regie`
- valable jusqu'en 2056

C'est une clé de signature de développement, volontairement publique : elle sert
à enchaîner les versions, pas à prouver quoi que ce soit. L'application n'est
pas sur un store, ne reçoit rien d'autre que la release du dépôt, et n'a aucun
secret à protéger.

Si un jour la clé doit devenir privée, la déposer en secret de dépôt
(`Settings → Secrets and variables → Actions`) sous forme base 64, la réécrire
dans un fichier au début du travail `apk` de `.github/workflows/apk.yml`, et
retirer ce dossier. Attention : changer de clé oblige à désinstaller
l'application une fois avant de réinstaller.
