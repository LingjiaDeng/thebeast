/* DO NOT EDIT THIS FILE - it is machine generated */
#include <jni.h>
/* Header for class thebeast_osi_CbcModel */

#ifndef _Included_thebeast_osi_CbcModel
#define _Included_thebeast_osi_CbcModel
#ifdef __cplusplus
extern "C" {
#endif
/*
 * Class:     thebeast_osi_CbcModel
 * Method:    createCbcModel
 * Signature: (J)J
 */
JNIEXPORT jlong JNICALL Java_thebeast_osi_CbcModel_createCbcModel
  (JNIEnv *, jclass, jlong);

/*
 * Class:     thebeast_osi_CbcModel
 * Method:    branchAndBound
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_thebeast_osi_CbcModel_branchAndBound
  (JNIEnv *, jobject, jlong);

/*
 * Class:     thebeast_osi_CbcModel
 * Method:    solver
 * Signature: (J)J
 */
JNIEXPORT jlong JNICALL Java_thebeast_osi_CbcModel_solver
  (JNIEnv *, jclass, jlong);

/*
 * Class:     thebeast_osi_CbcModel
 * Method:    referenceSolver
 * Signature: (J)J
 */
JNIEXPORT jlong JNICALL Java_thebeast_osi_CbcModel_referenceSolver
  (JNIEnv *, jclass, jlong);

/*
 * Class:     thebeast_osi_CbcModel
 * Method:    saveReferenceSolver
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_thebeast_osi_CbcModel_saveReferenceSolver
  (JNIEnv *, jclass, jlong);

/*
 * Class:     thebeast_osi_CbcModel
 * Method:    resetToReferenceSolver
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_thebeast_osi_CbcModel_resetToReferenceSolver
  (JNIEnv *, jclass, jlong);

/*
 * Class:     thebeast_osi_CbcModel
 * Method:    bestSolution
 * Signature: (J)[D
 */
JNIEXPORT jdoubleArray JNICALL Java_thebeast_osi_CbcModel_bestSolution
  (JNIEnv *, jobject, jlong);

/*
 * Class:     thebeast_osi_CbcModel
 * Method:    delete
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_thebeast_osi_CbcModel_delete
  (JNIEnv *, jobject, jlong);

/*
 * Class:     thebeast_osi_CbcModel
 * Method:    setLogLevel
 * Signature: (IJ)V
 */
JNIEXPORT void JNICALL Java_thebeast_osi_CbcModel_setLogLevel
  (JNIEnv *, jobject, jint, jlong);

#ifdef __cplusplus
}
#endif
#endif