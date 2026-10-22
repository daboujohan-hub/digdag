# DIG.DAG — messagerie chiffrée d'appareil à appareil

> Titre de travail (nom du dossier du projet). À renommer librement.

Messagerie légère qui fonctionne **sans Internet, sans crédit mobile et sans SMS**. Les téléphones qui ont l'application s'échangent les messages directement (Bluetooth / Wi-Fi Direct) et les font « sauter » de l'un à l'autre jusqu'au destinataire. Les messages sont **chiffrés de bout en bout** : les téléphones qui relaient ne peuvent pas les lire.

## Développeur

| | |
|---|---|
| Identifiant | **daboujohan** |
| Site | https://daboujohan-hub.github.io |
| E-mail | daboujohan@gmail.com |

## Principe

Le réseau tourne dans un **service Android au premier plan** (`MeshService`) : il continue de relayer les paquets chiffrés même écran éteint ou app fermée. L'interface HTML ne sert qu'à écrire, lire et chiffrer / déchiffrer ; elle récupère au réveil ce que le service a reçu.

```
Téléphone A ──► Téléphone B ──► Téléphone C ──► Téléphone D
expéditeur      relais          relais          destinataire
chiffre avec    transporte le   stocke 24 h et  vérifie l'intégrité
la clé de D     paquet chiffré  retransmet      et déchiffre avec sa clé
                (pas de clé)
```

Le trajet est un exemple : chaque liaison doit réellement être disponible pour que le paquet avance. Si personne n'est à portée, le message reste en attente sur le téléphone et part dès qu'un appareil apparaît.

## Fonctions

- Réseau maillé sans Internet (Google Nearby Connections, stratégie `P2P_CLUSTER`), 8 sauts maximum.
- Identifiant de 10 caractères créé à l'inscription, calculé à partir de la clé publique.
- Messages de 200 caractères maximum, chiffrés (ECDH P-256 + HKDF + AES-256-GCM).
- Stockage et retransmission 24 h (100 paquets) par les relais.
- Échange de contacts par **QR code** (contact vérifié en personne, marqué ✓).
- **Invitation** par QR code vers le site de téléchargement.
- Interface HTML unique et légère, empaquetée en APK avec Capacitor.

## Fichiers

| Fichier | Rôle |
|---|---|
| `www/index.html` | Interface, chiffrement, logique de relais, encodeur QR (aucune librairie externe) |
| `MeshService.java` | Service d'arrière-plan : garde le réseau actif écran éteint, relaie et stocke les paquets chiffrés 24 h |
| `MeshPlugin.java` | Pont entre l'interface HTML et le service |
| `MainActivity.java` | Enregistre le plugin dans Capacitor |

## Identité et identifiant

À l'inscription, l'app demande un nom, génère une paire de clés ECDH P-256 et calcule l'identifiant :

`ID = 10 caractères tirés de SHA-256(clé publique)` (alphabet sans 0/O/1/I).

Comme l'identifiant dépend de la clé, personne ne peut annoncer une fausse clé pour un identifiant existant. Il représente 50 bits : suffisant pour commencer. Pour une vraie garantie, confirme les contacts par QR code en face à face.

## Paquets

Deux types de paquets JSON, transmis tels quels par les relais :

- **Annonce `h`** : `{t, id, from, name, pub, ts, ttl}`. Diffuse le nom et la clé publique. Vérifiée à la réception (`ID == hash(pub)`).
- **Message `m`** : `{t, id, from, to, ts, ttl, iv, ct}`. `ct` est le texte chiffré. Les champs `id|from|to|ts` sont liés au contenu (données authentifiées AES-GCM) : si un relais les modifie, le déchiffrement échoue et le paquet est rejeté.

Chaque paquet porte un `ttl` réduit à chaque saut, et un identifiant unique pour éviter les boucles.

## Chiffrement

1. A calcule un secret commun avec la clé publique de D (ECDH P-256).
2. Une clé AES-256 en est dérivée (HKDF-SHA-256).
3. Le texte est chiffré en AES-GCM avec un IV aléatoire de 12 octets.
4. D refait le même calcul avec sa clé privée, vérifie l'intégrité et déchiffre.

Si A n'a pas encore la clé de D, le message reste local (« clé du destinataire pas encore reçue ») et part dès que l'annonce de D arrive, par un relais ou par QR code.

## QR codes

- **Mon contact** : `MSG1|ID|nom|clé publique`. Le scan ajoute le contact après vérification de l'identifiant.
- **Inviter** : adresse du site de téléchargement (`APP_URL` dans `index.html`). Pense à y publier l'APK.
- Le QR est généré dans l'app (octets, niveau de correction L, versions 1 à 10).
- Le scan utilise le plugin `@capacitor-mlkit/barcode-scanning`. À défaut, on peut copier / coller le code contact.

## Compiler l'APK sans Android Studio (GitHub Actions)

Le fichier `.github/workflows/apk.yml` copie le plugin et le service, ajoute la dépendance Nearby et les permissions, puis compile un APK de test.

1. Mets le projet sur GitHub (`MeshPlugin.java`, `MeshService.java`, `MainActivity.java`, `www/`, `android/`, `capacitor.config.json`, `package.json`, `package-lock.json`, `.github/`).
2. Onglet **Actions** > « Construire l'APK » > **Run workflow**.
3. Télécharge l'artefact `apk` (`app-debug.apk`) et installe-le sur le téléphone (autorise les sources inconnues).

L'APK est signé avec la clé de test : c'est suffisant pour essayer, pas pour une publication.

## Installation (Android Studio)

```bash
npm init -y
npm i @capacitor/core @capacitor/cli @capacitor/android @capacitor-mlkit/barcode-scanning
mkdir www            # y copier index.html
npx cap init Messagerie com.exemple.messagerie --web-dir=www
npx cap add android
```

Prends la version du plugin ML Kit de la même version majeure que Capacitor.

1. Copie `MeshPlugin.java`, `MeshService.java` et `MainActivity.java` dans `android/app/src/main/java/com/exemple/messagerie/` (remplace le `MainActivity.java` existant).
2. Dans `android/app/build.gradle`, section `dependencies` :
   `implementation 'com.google.android.gms:play-services-nearby:19.3.0'`
3. Dans `AndroidManifest.xml`, ajoute les permissions :

```xml
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30"/>
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30"/>
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE"/>
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE"/>
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" android:maxSdkVersion="32"/>
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="32"/>
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" android:minSdkVersion="31"/>
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" android:minSdkVersion="31"/>
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" android:minSdkVersion="31"/>
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" android:minSdkVersion="32"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"/>
<!-- dans <application> : -->
<service android:name=".MeshService" android:exported="false" android:foregroundServiceType="connectedDevice"/>
```

4. `npx cap sync android`, puis `npx cap open android` et Build > Build APK.

## Tests réalisés

- Encodeur QR : 13 textes de 5 à 271 octets (versions 1 à 10) décodés correctement par OpenCV ; les QR affichés dans l'app (contact et invitation) aussi.
- Chaîne A → C → B simulée dans un navigateur : annonces relayées, message livré à B, réponse de B livrée à A, le relais C ne stocke que du texte chiffré, un paquet modifié est rejeté, un code contact falsifié est refusé.
- **Non testé** : le vrai réseau Bluetooth / Wi-Fi sur téléphones et le scan caméra. À essayer avec 3 appareils Android.

## Statistiques

Chaque paquet porte un compteur de sauts (`ttl`, 8 au départ, réduit d'un à chaque relais). À la réception : **relais traversés = 8 − ttl**. L'écran Contacts > Statistiques affiche le nombre de messages reçus, les relais traversés (moyenne et maximum), le délai d'arrivée (approximatif : il compare les horloges de deux téléphones), le nombre de messages relayés par ce téléphone et d'appareils rencontrés. Chaque message reçu affiche aussi « N relais · durée ».

## Partager l'application

Contacts > Inviter > « Envoyer l'application à quelqu'un » copie l'APK de l'app installée et ouvre le menu de partage d'Android (Bluetooth, Partage à proximité, WhatsApp...). Cela marche sans Internet. Le QR code affiche en plus l'adresse du site.

## Identifiants : comment éviter les confusions

- L'identifiant (10 caractères) vient de l'empreinte SHA-256 de la clé publique : deux téléphones n'ont pas le même identifiant, et personne ne peut en annoncer un faux sans la clé correspondante.
- Alphabet de 32 caractères sans 0, O, 1 ni I, en majuscules : pas de confusion à la lecture ou à la saisie.
- 32^10 ≈ 10^15 possibilités : le risque que deux utilisateurs reçoivent le même identifiant par hasard est négligeable (environ 0,05 % avec un million d'utilisateurs).
- Les noms ne sont pas uniques : l'app affiche toujours **nom + 4 derniers caractères** (« Aminata #K7QX »), et la saisie du destinataire propose les contacts connus.
- Contact vérifié en personne par QR code : ✓.
- Réinstaller l'app crée de nouvelles clés, donc un **nouvel identifiant** (pas de sauvegarde pour l'instant).

## Appareils sans l'application

Un téléphone sans l'application ne peut pas relayer un message : le relais demande du code qui tourne sur l'appareil. Pistes :
1. Faciliter l'installation (bouton d'envoi de l'APK, sans Internet).
2. **Relais fixes** : un vieux téléphone Android avec l'app, branché en permanence dans une boutique ou une gare, relaie sans dépendre des passants.

## Test interne

Contacts > « Test interne » vérifie sur le téléphone, sans second appareil : chiffrement, rejet d'un paquet modifié, identifiants, QR code, et acceptation des paquets par le service réseau.

## Limites connues

- Le service d'arrière-plan affiche une notification permanente « Messagerie active ». Certaines marques (Tecno, Infinix, Xiaomi, etc.) arrêtent les apps en veille : utilise le bouton « Rester actif en arrière-plan » de l'écran Contacts, et désactive l'économie de batterie pour l'app.
- Nécessite Google Play Services (absent de certains téléphones).
- Portée d'un saut : environ 30 à 100 m. Il faut assez d'appareils entre l'expéditeur et le destinataire, ou du temps pour que des gens se déplacent.
- « Transmis » signifie « remis à un appareil proche », pas « lu ». Pas encore d'accusé de réception.
- Les relais voient l'expéditeur, le destinataire, l'heure et la taille, pas le contenu.
- Pas de confidentialité persistante : si une clé privée fuit, les anciens paquets enregistrés pourraient être lus.
- La clé privée est dans le stockage de l'app. À déplacer vers le Keystore Android.
- Le scan ML Kit peut demander une connexion une première fois pour installer son module via Google Play Services.

## Pistes

Accusés de réception, service d'arrière-plan, Keystore Android, messages de groupe, passerelle optionnelle vers un serveur quand Internet est disponible, langues locales.

## Licence

À choisir.
