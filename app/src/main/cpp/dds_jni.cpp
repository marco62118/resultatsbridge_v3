#include <jni.h>
#include <android/log.h>
#include <api/dll.h>

#define LOG_TAG "DDS_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Kotlin envoie cards[main*4+couleur] avec bitmask "1 shl rang" (rang 2..14).
// DdTableDeal.cards attend le même format : bit r pour rang r (r=2..14).
// Pas de conversion nécessaire — DDS fait lui-même >> 2 en interne (solver_if.cpp:114).
//
// Sortie : flat[denom*4+decl] = res_table[denom][decl] (nombre de levées)
//   denom 0=♠ 1=♥ 2=♦ 3=♣ 4=SA   — identique à l'ordre Kotlin
//   decl  0=N 1=E 2=S 3=W          — identique à l'ordre Kotlin

extern "C" JNIEXPORT jintArray JNICALL
Java_app_resultatsbridge_client_DdsCalculator_calculerTableNatif(
    JNIEnv* env, jobject /*thiz*/, jintArray jCards)
{
    if (env->GetArrayLength(jCards) != 16) {
        LOGE("Taille tableau incorrecte : attendu 16, recu %d",
             env->GetArrayLength(jCards));
        return nullptr;
    }

    jint* kotlinCards = env->GetIntArrayElements(jCards, nullptr);
    if (!kotlinCards) return nullptr;

    DdTableDeal tableDeal;
    for (int hand = 0; hand < DDS_HANDS; hand++) {
        for (int suit = 0; suit < DDS_SUITS; suit++) {
            // Format Kotlin : bit r actif pour rang r (r=2..14) — identique à DdTableDeal.cards
            tableDeal.cards[hand][suit] =
                static_cast<unsigned int>(kotlinCards[hand * 4 + suit]);
        }
    }
    env->ReleaseIntArrayElements(jCards, kotlinCards, JNI_ABORT);

    DdTableResults results;
    int ret = CalcDDtable(tableDeal, &results);

    if (ret != RETURN_NO_FAULT) {
        LOGE("CalcDDtable echec : code %d", ret);
        return nullptr;
    }

    LOGI("CalcDDtable OK — ♠N=%d ♥N=%d ♦N=%d ♣N=%d SAN=%d",
         results.res_table[0][0], results.res_table[1][0],
         results.res_table[2][0], results.res_table[3][0], results.res_table[4][0]);

    jint flat[20];
    for (int denom = 0; denom < DDS_STRAINS; denom++) {
        for (int decl = 0; decl < DDS_HANDS; decl++) {
            flat[denom * 4 + decl] = results.res_table[denom][decl];
        }
    }

    jintArray jResult = env->NewIntArray(20);
    env->SetIntArrayRegion(jResult, 0, 20, flat);
    return jResult;
}
