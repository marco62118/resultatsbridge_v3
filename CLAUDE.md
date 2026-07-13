# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Instructions générales

- Toujours répondre en français.
- Avant toute correction de bug, analyser tous les fichiers impactés et expliquer les conséquences sur l'ensemble du projet.
- Ne jamais faire un correctif partiel sans vérifier la cohérence globale.

## Build Commands

```bash
./gradlew assembleDebug          # Build debug APK
./gradlew assembleRelease        # Build release APK
./gradlew clean                  # Clean build artifacts
./gradlew test                   # Run unit tests
./gradlew connectedAndroidTest   # Run instrumented tests (requires connected device/emulator)
./gradlew test --tests "com.example.MyClass.myTest"  # Run a single test
```

## Architecture

**TournoiBridgeOnline** is a native Android app (Kotlin + Jetpack Compose) for managing Bridge card game tournaments. It supports two user roles: **Organisateur** (tournament director) and **Joueur** (player).

### Dual-mode networking model

- **Local mode**: The organizer's device runs an embedded HTTP server (NanoHTTPD on port 8080) that serves tournament data to players on the same LAN. Players connect via the organizer's local IP.
- **Online mode**: Players connect to the remote server at `https://resultats-bridge.alwaysdata.net/asso` (defined in `common/ClientConfig`).

### Package structure under `app/src/main/java/.../tournoibridgeonline/`

| Package | Role |
|---|---|
| `main/` | `MainActivity` (mode selection entry point), `BaseActivity` (forces fixed font scale/density) |
| `client/` | Player-side UI (`ClientActivity`), network layer (`ClientNetworkUtils`), UI state enum (`EtapeClient`) |
| `organisateur/` | Organizer UI (`OrganisateurActivity`), local HTTP server (`ServerManager`, `ClientServeurHTTP`), SQLite access (`DatabaseHelper`, `DatabaseManager`) |
| `screens/` | Shared Compose screens: bidding (`Encheres`), deal display (`AffichageDonne`), rankings (`Classement`), deal verification |
| `common/model/` | Domain entities: `Equipe`, `Joueur`, `Mouvement`, `Donne`, `Enchere`, etc. |
| `common/ui/components/` | Reusable Compose components (card view, Bridge-specific dropdowns) |

### Data persistence

The organizer module copies a pre-seeded `tournoi.db` SQLite file from `assets/` at first launch. `DatabaseHelper` manages schema/copy; `DatabaseManager` provides all CRUD operations. Players do not persist data locally.

### Key technology choices

- Jetpack Compose (UI) + Material3 theming
- NanoHTTPD 2.3.1 for the embedded HTTP server
- GSON 2.10.1 for JSON serialization over HTTP
- `androidx.security.crypto` for encrypted SharedPreferences
- Version catalog at `gradle/libs.versions.toml`; Compose BOM 2024.09.00

### Language note

All domain code, UI labels, and comments are in **French** (Bridge tournament terminology). Variable/function names follow French vocabulary (e.g., `equipe` = team, `joueur` = player, `donne` = deal, `enchere` = bid, `classement` = ranking).

---

## Rapport d'audit architectural — TournoiBridgeOnline

*Sections ✅ RÉSOLU vérifiées et corrigées le 11/07/2026 (Claude Code), directement dans le vrai code, pas juste supposées. Le point SSL ci-dessous s'est avéré être une fausse alerte.*

### Problèmes CRITIQUES

#### 1. Injection SQL — `DatabaseManager.php` — ✅ RÉSOLU 11/07/2026
`FROM $typeDeTournoi m` (et un second point similaire dans `getFuturMouvement`) → le type de tournoi, lu depuis la BD, était interpolé tel quel comme nom de table SQL. Comme `type` était accepté sans validation à la création (`creerTournoi` ET `importerTournoi`, deux points d'entrée), un type forgé aurait permis une injection SQL stockée, déclenchée à la première consultation du mouvement.
**Corrigé :** `DatabaseManager::estTypeTournoiValide()` (liste blanche : types calculés + table `type` de référence), appliquée à la création (source) et en défense en profondeur aux deux points d'interpolation.
Une deuxième injection SQL distincte (`enregistreDonne`, `$idTournoi` collé dans une requête `exec()`) a aussi été trouvée et corrigée le même jour (passée en requête préparée).

#### 2. Absence d'isolation entre associations (multi-tenant) — ✅ RÉSOLU 11/07/2026
La majorité des requêtes filtraient par `id_tournoi` sans vérifier que ce tournoi appartient à l'association authentifiée. Un club A pouvait lire/modifier les données du club B (confirmé exploitable sur ~15 endpoints de lecture ET sur 3 fonctions d'écriture qui recevaient `idAssociation` sans jamais s'en servir pour vérifier : `enregistreDonne`, `enregistrerEquipes`, `enregistrerMainsRelais`).
**Corrigé :** `DatabaseManager::tournoiAppartientA($idTournoi, $idAssociation)`, ajoutée en garde partout où c'était nécessaire (routeur `serverBridge.php` + directement dans les 3 fonctions d'écriture concernées). Protège aussi bien un token club qu'un token adhérent.
**Hors périmètre, confirmé sans importance par Marco :** isolation entre adhérents d'un même club (seule la frontière entre clubs comptait).
**Question encore ouverte :** `index.php`, `constitutionEquipes.php`, `participationMaitre.php` (mode local, sans authentification) — pas corrigées, en attente de savoir si elles sont déployées sur le serveur partagé ou utilisées seulement en local (un seul club à la fois, non concerné).

#### 3. SSL Trust-All — `ClientNetworkUtils.kt` — ❌ FAUSSE ALERTE (vérifié 11/07/2026)
Il ne s'agit **pas** d'un `X509TrustManager` qui accepte tout. C'est un `TrustManager` légitime dans `BridgeApplication.kt` qui ajoute les racines ISRG X1/X2 manquantes sur les vieux Android 6 (absentes de leur magasin de certificats système) — la chaîne de certificats est bien validée avant d'être acceptée, ce n'est pas un contournement.
En revanche le **token API en clair dans les paramètres GET**, mentionné dans le même point, était réel — voir point 17.

#### 4. Credentials SMTP en dur dans les fichiers PHP — CORRIGÉ le 09/07/2026
Mot de passe présent en clair dans 3+ fichiers PHP → tous passent en réalité par les constantes de `config.php` (fichier protégé par .htaccess, jamais commité dans ce dépôt). Mot de passe changé côté Alwaysdata (DB + SMTP) suite à l'exposition de l'ancien dans ce fichier et dans `docs/documentation_complete.md`.

---

### Problèmes HAUTS

#### 5. État global mutable non thread-safe — `TournoiConfig.kt`
`object TournoiConfig` avec `var NBRE_MOUVEMENTS`, `NBRE_TABLES`, `EQUIPE_RELAIS`, `NBRE_DONNES_PAR_TABLE` modifiés depuis plusieurs threads sans synchronisation → race conditions.

#### 6. Mega-classe `ClientActivity.kt` (839 lignes, 0 ViewModel)
Gère : 17 états navigation (`EtapeClient`), appels réseau, accès SQLite, 25+ variables d'état, toute l'UI Compose. Pas de ViewModel, pas de Repository.

#### 7. Gestion d'erreur réseau incomplète — `ClientActivity.kt:149-164`
Appels réseau dans `LaunchedEffect` sans try/catch → crash silencieux si exception. Pas de mécanisme de retry.

#### 8. Mot de passe stocké dans SharedPreferences — `ConnexionScreen.kt:86-87`
`lastMdp` sauvegardé dans EncryptedSharedPreferences. Stocker le token de session suffit.

#### 9. Double finalisation concurrente — `enregistreDonne` PHP
Quand `nbre_enregistrement` atteint 0, `finaliserClassementTournoi()` est appelé. Si 2 équipes enregistrent simultanément la dernière donne → double exécution possible.
**Correctif :** verrou `SELECT FOR UPDATE` sur `tournois` dans la transaction.

---

### Problèmes MOYENS

#### 10. Navigation par enum fragile — `EtapeClient.kt` + `ClientActivity.kt`
17 états, `etapeRetourDetails` en mémoire locale perdue si Activity recrée. Pas de Jetpack Navigation.

#### 11. Curseurs SQLite sans try-finally — `DatabaseManager.kt:38-48`
`cursor.close()` sans protection `finally` → fuite de curseur si exception.

#### 12. Pas de transactions SQLite — `ClientServeurHTTP.kt:261-278`
Enregistrement donne en plusieurs étapes sans transaction → BD incohérente si échec partiel.

#### 13. Duplication de code
- Création `EncryptedSharedPreferences` (~70 lignes) copiée entre `ConnexionScreen.kt` et `OrganisateurActivity.kt`.
- Logique reconstruction `Mouvement` dupliquée entre `ClientNetworkUtils.kt` et `ClientServeurHTTP.kt`.

#### 14. Format non-JSON `verifierTournoiOuvert` — `serverBridge.php`
Retourne du texte pipe-delimited `id|type|nbreMouvements|nbreDonnesParTable|equipeRelais`. Seul endpoint non-JSON → fragile.
**Correctif :** retourner JSON comme tous les autres endpoints.

#### 15. Erreurs internes exposées au client — `DatabaseManager.php`
`return MouvementResult::Erreur("Erreur serveur: " . $e->getMessage())` révèle noms de tables/colonnes/requêtes SQL.
**Correctif :** logger côté serveur, retourner message générique.

#### 16. Timeouts réseau incohérents — `ClientNetworkUtils.kt`
3 000 ms, 6 000 ms, 15 000 ms selon les fonctions sans logique apparente.

#### 17. Token API en clair dans l'URL — `ClientNetworkUtils.kt` — ✅ RÉSOLU 11/07/2026
Le token (club et adhérent) était transmis en paramètre GET → visible dans les journaux d'accès du serveur (pas d'interception réseau : l'appli utilise bien du HTTPS avec vérification de certificat valide, voir point 3).
**Corrigé :** déplacé vers un en-tête HTTP (`X-Token-Api` / `X-Token-Joueur`), côté serveur (`serverBridge.php`, rétrocompatible avec l'ancien GET pour les appareils pas encore mis à jour) et côté appli (`ClientNetworkUtils.kt`, `OrganisateurActivity.kt`).

#### 18. Pas de rate limiting sur reset mot de passe — ✅ RÉSOLU 11/07/2026
Code 6 chiffres, valide 15 min, sans blocage après X tentatives → force brute possible.
**Corrigé :** limité à 5 tentatives (`LIMITE_TENTATIVES_CODE` dans `fonctions.php`), colonne `tentatives` ajoutée à `reset_tokens`, dans `api_adherent.php` et `api_club.php`.

---

### Bugs cycle mouvement/relais — correctifs validés

#### Bug A — `DatabaseManager.php` lignes 729-732
`getMouvementPourEquipe` PHP fait un UPDATE BD alors que Kotlin ne le fait jamais.
**Correctif :** supprimer entièrement le bloc :
```php
if ($tousTermines) {
    $pdo->prepare("UPDATE equipes SET mvnt_numero=?, index_donne_jouee=? WHERE id_tournoi=? AND equipe_numero=?")
        ->execute([$mvntNumero, -1, $idTournoi, $equipeNumero]);
}
```

#### Bug B — `serverBridge.php` ligne 391
`passerTableRelais` appelle `incrementerMouvementEquipe` → double incrément de `mvnt_numero`.
**Correctif :** créer `DatabaseManager::passerTableRelais()` qui fait uniquement `mvnt+1, index=nbre-1` SANS toucher à `nbre_enregistrement`.

#### Bug C — `incrementerMouvementEquipe` PHP et Kotlin
`index = nbre_donnes_par_table - 1` → pour nbre=1, index=0, mouvement paraît déjà terminé.
**Correctif :** `index = -1` dans les deux fichiers.

#### Bug D — `ClientActivity.kt` ligne 484
`mvntTermineNumero.value = mvnt.mvntNumero` → faux, `mvntNumero` est déjà le futur calculé en mémoire.
**Correctif :** `mvntTermineNumero.value = mvnt.mvntNumero - 1`

---

### Neuberg + score% — état au 25/04/2026

Modifications **appliquées** dans les fichiers suivants, APK **non encore rebuilé ni testé** :

| Fichier | Changement |
|---|---|
| `ClassementItem.kt` | Ajout `scorePct: Double = 0.0` |
| `ClassementManager.kt` | Formule Neuberg + Ntop + `dealsParEquipe` + `scorePct` |
| `DatabaseManager.kt` `getClassementStocke` | Calcul `scorePct` depuis BD |
| `ClassementScreen.kt` | Affichage `"62,5 %"` sous les pts |
| `ClientActivity.kt` | `scorePct = c.scorePct` |
| `ClientServeurHTTP.kt` | `put("scorePct", c.scorePct)` dans JSON |
| `ClientNetworkUtils.kt` | `scorePct = o.optDouble("scorePct", 0.0)` |
| `CalculClassementManager.php` | `scorePct` dans chaque item classement |

Formule Neuberg : `scoreAjuste = ((N × (scoreReel + 1)) / n) − 1`
Score% : `(ptsObtenus / (nDonnesJouées × Ntop)) × 100`

---

### Tableau de synthèse

| Sévérité | Problème | Fichier | État |
|---|---|---|---|
| CRITIQUE | Injection SQL (`$typeDeTournoi` + `enregistreDonne`) | `DatabaseManager.php` | ✅ Résolu 11/07 |
| CRITIQUE | Pas d'isolation entre associations | Tous les endpoints PHP | ✅ Résolu 11/07 (sauf 3 pages locales, question ouverte) |
| CRITIQUE | Credentials SMTP en dur | Fichiers PHP | ✅ Résolu 09/07 |
| CRITIQUE | SSL Trust-All | `ClientNetworkUtils.kt` | ❌ Fausse alerte |
| CRITIQUE | Token en GET | `ClientNetworkUtils.kt` | ✅ Résolu 11/07 |
| HAUTE | Double finalisation concurrente | `enregistreDonne` PHP | Non traité |
| HAUTE | Mega-classe sans ViewModel | `ClientActivity.kt` | Non traité (dette technique) |
| HAUTE | Mot de passe en SharedPreferences | `ConnexionScreen.kt` | Non traité |
| MOYENNE | Pas de rate limiting reset mdp | `api_club.php`, `api_adherent.php` | ✅ Résolu 11/07 |
| MOYENNE | Erreurs internes exposées | `DatabaseManager.php` | Non traité |
| MOYENNE | TournoiConfig non thread-safe | `TournoiConfig.kt` | Non traité |
| MOYENNE | Curseurs SQLite sans try-finally | `DatabaseManager.kt` | Non traité |
| MOYENNE | verifierTournoiOuvert format texte | `serverBridge.php` | Non traité |
| BASSE | Zéro test unitaire | Android + PHP | Non traité |
| BASSE | Logs excessifs en production | `ClientNetworkUtils.kt` | Non traité |

---

## Fonctionnalité — Lien public par tournoi

### Objectif
Permettre à un joueur externe (sans compte) de consulter les résultats d'un tournoi via un lien partagé par l'organisateur. L'adhérent du club garde son accès habituel (email + mdp, liste complète de ses tournois).

### Deux chemins de publication
- **Tournoi local** (le plus courant) : organisateur joue en LAN, puis exporte via l'app Android → `/importerTournoi` → `token_public` généré à l'import.
- **Tournoi online** : `/getResultatsTournoi` → `token_public` généré à la finalisation du classement.

### Règles
- Chaque tournoi finalisé reçoit automatiquement un `token_public` (32 hex chars, `bin2hex(random_bytes(16))`).
- Le lien public : `resultats_tournoi.html?id=X&tokenPublic=<token>`
- Avec le lien : accès à ce tournoi uniquement, sans compte.
- Sans le lien : impossible d'accéder (token aléatoire, non devinable).
- L'adhérent du club garde son accès via `resultats.html` (email + mdp) — inchangé.

### Fichiers modifiés
| Fichier | Changement |
|---|---|
| BD | `ALTER TABLE tournois ADD COLUMN token_public VARCHAR(32) DEFAULT NULL` |
| `DatabaseManager.php` | `importerTournoi` : génère + stocke `token_public`. `getTournoisTermines` : retourne `token_public`. Nouvelles méthodes : `getDetailTournoiParTokenPublic`, `getDonneCompleteParTokenPublic`, `genererOuGetTokenPublic` |
| `serverBridge.php` | `getResultatsTournoi` : génère `token_public` + le retourne dans la réponse |
| `api_public.php` | Nouveau fichier — endpoint sans auth, actions `tournoi` + `donne` |
| `api_club.php` | Nouvelle action `tournois` — retourne liste avec `token_public` |
| `resultats_tournoi.html` | Lit `tokenPublic` en URL, route vers `api_public.php` si présent |
| `mon_compte.html` | Affiche le lien public copiable pour chaque tournoi terminé |
