#ifndef _ABIPICKER_H_
#define _ABIPICKER_H_

#include <jni.h>
#include <stdlib.h>
#include <utils/Vector.h>
#include <sys/types.h>

#include <nativehelper/ScopedUtfChars.h>

namespace android {
//  assumption: the length of name of any abi type in abi list,
//  like armeabi-v7a, armeabi, x86, is not longer than 64
#define ABI_NAME_MAX_LENGTH     (64)

class ABIPicker {
 public:
    explicit ABIPicker(const char* pkgName,Vector<ScopedUtfChars*> abiList);
    ~ABIPicker(void);

    bool buildNativeLibList(void* apkHandle);
    int  pickupRightABI(int sysPrefer);
 private:
    struct libInfo{
        char abiName[ABI_NAME_MAX_LENGTH];
        Vector<char*>* libNameList;
    };
    Vector<struct libInfo*>* mLibList;
    char* mpkgName;

    bool foundMixedELF(const char* abiName);
    bool compare(char* armRef, char* iaRef, char* rawResult, char** result);
    bool compareLibList(Vector<char*>& iaRefList, Vector<char*>& armRefList);
    bool compare3rdPartyLibList( char* iaRef, char* armRef,
            size_t* iaIsvLibCount, size_t* armIsvLibCount);
    char*  getAbiName(int abi);
    int    getAbiIndex(const char* abiName);
    bool isABILibValid(const char* abiName);
    Vector<char*>* getLibList(const char* abiName);
};

bool isInOEMWhiteList(const char* pkgName);
}  // namespace android
#endif  // _ABIPICKER_H_
