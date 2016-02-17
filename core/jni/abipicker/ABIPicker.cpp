#define LOG_TAG "ABIPicker"

#include "abipicker/ABIPicker.h"
#include "abipicker/ELFLite.h"

#include <androidfw/ZipFileRO.h>
#include <androidfw/ZipUtils.h>

namespace android {
#define ARR_SIZE(x)     (sizeof(x)/sizeof(x[0]))

#define SO_NAME_MAX (4096)
#define IMPOSSIBLE_LIB_NAME    "/mixed/"
#define IMPOSSIBLE_LIB_LEN  (sizeof(IMPOSSIBLE_LIB_NAME)-1)
#define ARMABI      "armeabi"
#define ARMV7ABI    "armeabi-v7a"
#define ARM64ABI    "arm64-v8a"
#define X86ABI      "x86"
#define X8664ABI    "x86_64"
#define ARMABI_NAME_PREFIX        "arm"

#define APK_LIB "lib/"
#define APK_LIB_LEN (sizeof(APK_LIB) - 1)
//#define PICK_LOGGER //flag to debug
#ifdef PICK_LOGGER
#define P_LOG(...) ALOGI(__VA_ARGS__)
#else
#define P_LOG(...)
#endif

#define OEMWHITE  "/vendor/etc/misc/.OEMWhiteList"
#define OEMBLACK  "/vendor/etc/misc/.OEMBlackList"
#define THIRDPARTY "/vendor/etc/misc/.ThirdPartySO"

// load once, hold until poweroff
static Vector <char*> thirdPartySO;
static Vector <char*> cfgWhite;
static Vector <char*> cfgBlack;
static bool thirdload = false;
static bool whiteload = false;
static bool blackload = false;

static const char* iaRelated[] = {"intel", "atom", "x86", "x64"};

//////////////////////////////////////////////////////////////////////
void getConfig(const char* cfgFile , Vector<char*>& cfgVec) {
    FILE* fp = fopen(cfgFile, "r");
    int read = -1;
    char *line = NULL;
    size_t len = 0;

    while ((read = getline(&line, &len, fp)) != -1) {
        int i = 0 , j = 0;
        char *cfgline = (char*)malloc(len);
        if (!cfgline) {
           P_LOG("malloc error");
           break;
        }
        for (i = 0; i < read; i++) {
           if (!isspace(line[i])) {
              cfgline[j++] = line[i];
           }
        }
        cfgline[j] = '\0';
        cfgVec.push_back(cfgline);
        P_LOG("orignal %s , vec: %s ", line, cfgline);
    }
    free(line);
    fclose(fp);
}

//////////////////////////////////////////////////////////////////////
void freeAllString(Vector<char*>& list) {
    Vector<char*>::iterator it = list.begin();
    while (it != list.end()) {
        if (*it != NULL) {
           P_LOG("freeAllSring  %p , %s", it, *it);
           free(*it);
           *it = NULL;
        }
        it++;
    }
}

//////////////////////////////////////////////////////////////////////
bool isInOEMWhiteList(const char* pkgName) {
    bool result = false;
    if (!pkgName) return result;

    if (!whiteload) {
       getConfig(OEMWHITE, cfgWhite);
       whiteload = true;
    }

    Vector<char*>::iterator it = cfgWhite.begin();
    for (; it != cfgWhite.end(); it++) {
       P_LOG("whitelist : %s", *it);
       if (0 == strcmp(pkgName, *it)) {
          ALOGI("found %s in whitelist", pkgName);
          result = true;
          break;
       }
    }
    return result;
}

//////////////////////////////////////////////////////////////////////
bool isInOEMBlackList(const char* pkgName) {
    bool result = false;
    if (!pkgName) return result;

    if (!blackload) {
       getConfig(OEMBLACK,  cfgBlack);
       blackload = true;
    }

    Vector<char*>::iterator it = cfgBlack.begin();
    for (; it != cfgBlack.end(); it++) {
       if (0 == strcmp(pkgName, *it)) {
          ALOGI("found %s in blacklist", pkgName);
          result = true;
          break;
       }
    }
    return result;
}


//////////////////////////////////////////////////////////////////////
bool isReliableLib(Vector<char*>& libList) {
    unsigned sz = libList.size();
    int len = ARR_SIZE(iaRelated);
    for (unsigned i = 0; i < sz; i++) {
        for (int j=0; j < len; j++) {
            if (NULL != strstr(libList[i], iaRelated[j])) {
                return true;
            }
        }
    }

    return false;
}


//////////////////////////////////////////////////////////////////////
static bool isValidELF(char* buffer) {
    if (buffer[EI_MAG0] != ELFMAG0 &&
        buffer[EI_MAG1] != ELFMAG1 &&
        buffer[EI_MAG2] != ELFMAG2 &&
        buffer[EI_MAG3] != ELFMAG3) {
        return false;
    }
    return true;
}

// assume that x86 has the only machine-code 3, and x86_64 62
static bool isMixedLib(char* libCur, char* buffer) {
    bool isX86_64 = (0 == strcmp(libCur, X8664ABI)) ? true: false;
    uint16_t machine_code = *((uint16_t*)(&buffer[ELF_MACHINE_OFFSET]));
    bool mixed = false;
    if (isX86_64) {
        if (machine_code != EM_X86_64)
            mixed = true;
    } else {
        if (machine_code != EM_386)
            mixed = true;
    }
    return mixed;
}

static bool isInThirdPartySOList(char* libName) {
    if (!libName) return false;
    size_t libLen = strlen(libName);
    bool ret = false;
    size_t sz = thirdPartySO.size();
    for (size_t i = 0; i < sz; i++) {
        size_t n = strlen(thirdPartySO[i]);
        // three for prefix "lib", and 3 for suffix ".so"
        if ((libLen == (n+6))&&(0 == strncmp(libName + 3, thirdPartySO[i], n))) {
            ret = true;
            break;
        }
    }
    P_LOG("ABIpicker libName %s,In Third %d", libName, ret);
    return ret;
}

static void insertionSort(Vector<char*>& list) {
    P_LOG("in insertionSort, list size = %d\n", list.size());

    for (size_t i = 1; i < list.size(); i++) {
        char* x = list[i];

        int j = i - 1;
        P_LOG("sort 1. x=%s, i=%d, j=%d\n", x, i, j);
        while (j >= 0 && (strcmp(list[j], x) > 0)) {
            list.replaceAt(list[j], j + 1);
            j--;
        }
        list.replaceAt(x, j + 1);
    }
}



//////////////////////////////////////////////////////////////////////
// Use armRef as a reference, compare all libraries of iaRef with all
// libraries of armRef. If both are match, iaRef will be returned with
// *result and true is return value. Or else, *result is rawResult and
// false is return value
bool ABIPicker::compare(char* armRef, char* iaRef,
                        char* rawResult, char** result) {
    bool ret = true;
    *result = rawResult;

    do {
        assert(armRef != NULL);
        if (0 == strlen(armRef)) {
            *result = strlen(iaRef) > 0 ? iaRef : rawResult;
            ret = strlen(iaRef) > 0 ? true : false;
            break;
        }
        assert(iaRef != NULL);
        if (0 == strlen(iaRef)) {
            *result = armRef;
            ret = false;
            break;
        }

        Vector<char*>* iaRefList = getLibList(iaRef);
        Vector<char*>* armRefList = getLibList(armRef);

        // if contains the key words in iaRelated, just return true
        if (isReliableLib(*iaRefList)) {
            *result = iaRef;
            break;
        }

        if (compareLibList(*iaRefList, *armRefList)) {
            *result = iaRef;
            break;
        }

        size_t iaIsvLibCount = 0;
        size_t armIsvLibCount = 0;
        if (!compare3rdPartyLibList(iaRef, armRef,
                    &iaIsvLibCount, &armIsvLibCount)) {
            *result = armRef;
            ret = false;
            break;
        }

        if (iaIsvLibCount > 0) {
            *result = iaRef;
            break;
        }

        *result = armRef;
        ret = false;
    } while (false);

    ALOGV("%s Vs. %s, return %s\n",
            iaRef ? iaRef : "NULL",
            armRef ? armRef : "NULL", *result);
    return ret;
}

bool ABIPicker::compareLibList(Vector<char*>& iaRefList,
        Vector<char*>& armRefList) {
    if (iaRefList.size() != armRefList.size()) {
        return false;
    }

    Vector<char*>::iterator itIa = iaRefList.begin();
    Vector<char*>::iterator itArm = armRefList.begin();
    while (itIa != iaRefList.end() && itArm != armRefList.end()) {
        char* iaLibName = *itIa;
        char* armLibName = *itArm;

        // NOTE:
        // WIN treats file names in-case-sensitive,
        // but LINUX  treats them case-sensitive.
        if (0 != strcmp(iaLibName, armLibName)) {
            return false;
        }

        itIa++;
        itArm++;
    }

    return true;
}

bool ABIPicker::compare3rdPartyLibList(
                char* iaRef, char* armRef,
                size_t* iaIsvLibCount, size_t* armIsvLibCount) {
    Vector<char*>* iaRefList = getLibList(iaRef);
    Vector<char*>* armRefList = getLibList(armRef);

    Vector<char*>* armRef3rdPartyLibList = new Vector<char*>();
    Vector<char*>::iterator itArm = armRefList->begin();

    // Load thirdPartyso
    if (!thirdload) {
        getConfig(THIRDPARTY, thirdPartySO);
        thirdload = true;
    }
    while (itArm != armRefList->end()) {
        char* armLibName = *itArm;
        if (isInThirdPartySOList(armLibName)) {
            armRef3rdPartyLibList->push_back(armLibName);
        } else {
            (*armIsvLibCount)++;
        }

        itArm++;
    }

    Vector<char*>::iterator itIa = iaRefList->begin();
    Vector<char*>* iaRef3rdPartyLibList = new Vector<char*>();
    while (itIa != iaRefList->end()) {
        char* iaLibName = *itIa;
        if (isInThirdPartySOList(iaLibName)) {
            iaRef3rdPartyLibList->push_back(iaLibName);
        } else {
            (*iaIsvLibCount)++;
        }

        itIa++;
    }
    return compareLibList(*iaRef3rdPartyLibList, *armRef3rdPartyLibList);
}

char* ABIPicker::getAbiName(int abi) {
    if (abi <  0 || (unsigned)abi >= mLibList->size()) {
        return NULL;
    }

    char* ret = NULL;
    int index = 0;
    Vector<struct libInfo*>::iterator it = mLibList->begin();
    while (it != mLibList->end()) {
        if (index == abi) {
            ret = (*it)->abiName;
            break;
        }

        index++;
        it++;
    }

    return ret;
}

int ABIPicker::getAbiIndex(const char* abiName) {
    int ret = -1;

    int index = 0;
    Vector<struct libInfo*>::iterator it = mLibList->begin();
    while (it != mLibList->end()) {
        if (0 == strcmp(abiName, (*it)->abiName)) {
            ret = index;
            break;
        }

        index++;
        it++;
    }

    return ret;
}

Vector<char*>* ABIPicker::getLibList(const char* abiName) {
    Vector<char*>* ret = NULL;
    Vector<struct libInfo*>::iterator it = mLibList->begin();
    while (it != mLibList->end()) {
        if (0 == strcmp(abiName, (*it)->abiName)) {
            ret = (*it)->libNameList;
            break;
        }
        it++;
    }
    P_LOG("getLibList of %s return %p\n", abiName, ret);
    return ret;
}


size_t ABIPicker::getSpecficABILibCount(const char* abiName) {
    Vector<char*>* specificAbiLibList = getLibList(abiName);
    return specificAbiLibList && specificAbiLibList->size();
}

bool ABIPicker::foundMixedELF(const char* abiName) {
    Vector<char*>* libNameList = getLibList(abiName);
    if (!libNameList) {
        return false;
    }
    if (libNameList->size() == 0) {
        return false;
    }

    if (0 != strcasecmp(*(libNameList->begin()), IMPOSSIBLE_LIB_NAME)) {
        return false;
    }

    return true;
}


//////////////////////////////////////////////////////////////////////
ABIPicker::ABIPicker(const char* pkgName, Vector<ScopedUtfChars*> abiList) {
    mLibList = new Vector<struct libInfo*>();
    mpkgName = (char*)malloc(strlen(pkgName)+1);
    if (!mpkgName) {
        P_LOG("ABIPicker Construct Allocated space fails");
    } else {
        strcpy(mpkgName, pkgName);
    }
    Vector<ScopedUtfChars*>::iterator it = abiList.begin();
    while (it != abiList.end()) {
        if (!(*it)) {
            break;
        }

        struct libInfo* tmp = (struct libInfo*)calloc(1,
                sizeof(struct libInfo));
        if (!tmp) {
           P_LOG("ABIPicker Construct Allocated space fail %s", (*it)->c_str());
           break;
        }

        snprintf(tmp->abiName, (*it)->size() + 1, "%s", (*it)->c_str());
        tmp->libNameList = new Vector<char*>();
        P_LOG("ABIPicker Construct %s , libNameList: %p",
             tmp->abiName, tmp->libNameList);
        mLibList->push_back(tmp);
        it++;
    }
}

ABIPicker::~ABIPicker(void) {
    free(mpkgName);
    mpkgName = NULL;
    Vector<struct libInfo*>::iterator it = mLibList->begin();
    while (it != mLibList->end()) {
        freeAllString(*((*it)->libNameList));
        (*it)->libNameList->clear();
        delete (*it)->libNameList;
        (*it)->libNameList = NULL;

        free(*it);
        *it = NULL;
        it++;
    }
    mLibList->clear();
}

bool ABIPicker::buildNativeLibList(void* apkHandle) {
    bool ret = false;

    if (!apkHandle) {
        ALOGE("apkHandle is NULL\n");
        return ret;
    }

    ZipFileRO* zipFile = reinterpret_cast<ZipFileRO*>(apkHandle);
    void* cookie = NULL;
    if (!zipFile->startIteration(&cookie)) {
        ALOGE("apk file is broken\n");
        return ret;
    }

    ZipEntryRO next = NULL;
    char* unCompBuff = NULL;
    char fileName[SO_NAME_MAX + 1];
    while ((next = zipFile->nextEntry(cookie))) {
        if (zipFile->getEntryFileName(next, fileName, SO_NAME_MAX)) {
            ALOGE("apk file is broken, can not get entry name\n");
            ret = false;
            break;
        }
        fileName[SO_NAME_MAX] = '\0';

        // Make sure we're in the lib directory of the ZIP.
        // find out entries with such names: "lib/xxxxxxx" or "lib/"
        if (strncmp(fileName, APK_LIB, APK_LIB_LEN)) {
            continue;
        }

        // find out any invalid ELF file
        uint32_t unCompLen = 0;
        if (!zipFile->getEntryInfo(next, NULL, &unCompLen, NULL, NULL, NULL,
                                    NULL)) {
            ALOGE("apk file is broken, can not get entry info\n");
            ret = false;
            break;
        }

        if (unCompLen == 0) {
            ALOGV("skip a empty file(%s)\n", fileName);
            continue;
        }

        free(unCompBuff);
        unCompBuff = NULL;

        unCompBuff = (char*)malloc(unCompLen);
        if (!unCompBuff) {
            ALOGE("malloc failed size %d\n", unCompLen);
            ret = false;
            break;
        }

        // THE MOST TIME COST OPERATION
        if (!zipFile->uncompressEntry(next, unCompBuff, unCompLen)) {
            ALOGE("%s: uncompress failed\n", fileName);
            ret = false;
            break;
        }

        if (!isValidELF(unCompBuff)) {
            ALOGI("skip a fake .ELF file(%s)\n", fileName);
            continue;
        }

        // It is a real .so file, prepare to record
        // find abi name and focus on what we care: arm(s) and x86(s)
        // at least lastSlash points to the end of "lib/"
        const char* lastSlash = strrchr(fileName, '/');
        const char* cpuAbiOffset = fileName + APK_LIB_LEN;
        // just in case if fileName is in an abnormal format, like lib/libname,
        // lib//libname
        if (lastSlash <= cpuAbiOffset) {
            ALOGI("skip a invalid lib file(%s)\n", fileName);
            continue;
        }

        const size_t cpuAbiRegionSize = lastSlash - cpuAbiOffset;
        char curAbiName[ABI_NAME_MAX_LENGTH];
        if (cpuAbiRegionSize >= ABI_NAME_MAX_LENGTH) {
            continue;
        }
        snprintf(curAbiName, cpuAbiRegionSize + 1, "%s", cpuAbiOffset);

        Vector<char*>* libListOfCurAbi = getLibList(curAbiName);
        if (!libListOfCurAbi) {
            P_LOG("getLibList of %s return NULL\n", curAbiName);
            continue;
        }

        // mixed arm elf in lib/x86 or lib/x86_64
        // but we don't consider a compareable scenario in lib/arm*
        if (0 == strcmp(curAbiName, X86ABI) ||
                0 == strcmp(curAbiName, X8664ABI)) {
            if (!libListOfCurAbi->empty()) {
                char* firstElement = libListOfCurAbi->itemAt(0);
                if (0 == strcmp(firstElement, IMPOSSIBLE_LIB_NAME)) {
                    // won't add any new into the list if found mixed
                    // lib before
                    P_LOG("won't count count if found mixed lib before");
                    continue;
                }
            }

            if (isMixedLib(curAbiName, unCompBuff)) {
                P_LOG("found mixed lib(%s) in lib/%s/", curAbiName, fileName);
                freeAllString(*libListOfCurAbi);
                libListOfCurAbi->clear();
                char* mixedLib = (char*)malloc(IMPOSSIBLE_LIB_LEN+1);
                if (!mixedLib) {
                    ALOGE("malloc failed size %zu", IMPOSSIBLE_LIB_LEN + 1);
                    ret = false;
                    break;
                }
                strcpy(mixedLib, (char*)IMPOSSIBLE_LIB_NAME);
                mixedLib[IMPOSSIBLE_LIB_LEN] ='\0';
                libListOfCurAbi->push_back(mixedLib);
                continue;
            }
        }

        // now, lastSlash should point to lib name
        lastSlash++;
        const size_t libNameSize = strlen(lastSlash);
        char* curLibName = (char*)malloc(libNameSize+1);
        if (!curLibName) {
            ALOGE("malloc failed size %zu\n", libNameSize+1);
            ret = false;
            break;
        }
        strcpy(curLibName, lastSlash);
        curLibName[libNameSize] = '\0';

        libListOfCurAbi->push_back(curLibName);

        ret = true;
    }

    free(unCompBuff);
    unCompBuff = NULL;

    zipFile->endIteration(cookie);

    for (unsigned i = 0; i < mLibList->size(); i++) {
        struct libInfo* tmp = mLibList->itemAt(i);
        insertionSort(*(tmp->libNameList));
    }
    return ret;
}

int ABIPicker::pickupRightABI(int sysPrefer) {
    char* sysPreferAbiName = getAbiName(sysPrefer);
    if (!sysPreferAbiName) {
        return sysPrefer;
    }

    bool is64BitPrefer = (0 == strcmp(sysPreferAbiName, X8664ABI));
    bool x8664HasMixedELF = foundMixedELF(X8664ABI);
    bool x86HasMixedELF = foundMixedELF(X86ABI);

    size_t armv7LibCount = getSpecficABILibCount(ARMV7ABI);
    size_t armv5LibCount = getSpecficABILibCount(ARMABI);
    size_t armv8LibCount = getSpecficABILibCount(ARM64ABI);
    size_t x86LibCount = x86HasMixedELF ? 0 : getSpecficABILibCount(X86ABI);
    size_t x8664LibCount = x8664HasMixedELF ? 0 : getSpecficABILibCount(X8664ABI);
    P_LOG("armv7LibCount:%d armv5LibCount:%d armv8LibCount:%d x86LibCount:%d x8664LibCount:%d", armv7LibCount, armv5LibCount, armv8LibCount, x86LibCount, x8664LibCount);

    // in OEMBlackList, need to be supported by bt
    // but in case of armlib doesn't exist, we choose x86 or x86_64
    if (isInOEMBlackList(mpkgName)) {
        if (armv7LibCount > 0) {
            return getAbiIndex(ARMV7ABI);
        } else if (armv5LibCount > 0) {
            return getAbiIndex(ARMABI);
        } else if (armv8LibCount > 0) {
            return getAbiIndex(ARM64ABI);
        }
    }

    char arm64Ref[ABI_NAME_MAX_LENGTH];
    if (armv8LibCount > 0) {
        snprintf(arm64Ref, sizeof(ARM64ABI), "%s", ARM64ABI);
    } else {
        arm64Ref[0] = '\0';
    }

    char arm32Ref[ABI_NAME_MAX_LENGTH];
    if (armv7LibCount > 0) {
        snprintf(arm32Ref, sizeof(ARMV7ABI), "%s", ARMV7ABI);
    } else if (armv5LibCount > 0) {
        snprintf(arm32Ref, sizeof(ARMABI), "%s", ARMABI);
    } else {
        arm32Ref[0] = '\0';
    }

    char ia32Ref[ABI_NAME_MAX_LENGTH];
    if (x86LibCount > 0) {
        snprintf(ia32Ref, sizeof(X86ABI), "%s", X86ABI);
    } else {
        ia32Ref[0] = '\0';
    }

    char ia64Ref[ABI_NAME_MAX_LENGTH];
    if (x8664LibCount > 0) {
        snprintf(ia64Ref, ABI_NAME_MAX_LENGTH, "%s", X8664ABI);
    } else {
        ia64Ref[0] = '\0';
    }

    char* retAbiName = sysPreferAbiName;
    do {
        // # The basic rule is:
        // - on 32 bit system, compare ia32Ref native libraries with
        // arm32Ref native libraries. If pass, return ia32Ref .
        // If fail, return arm32Ref.
        // - on 64 bit system, IA has two chances. if ia64Ref native
        // libraries can't pass the comparation with arm64Ref, we should
        // run the comparation again with ia32Ref
        if (is64BitPrefer) {
            if (!compare(arm64Ref, ia64Ref, sysPreferAbiName, &retAbiName)) {
                char rawRes[ABI_NAME_MAX_LENGTH];
                strcpy(rawRes, retAbiName);
                compare(arm32Ref, ia32Ref, rawRes, &retAbiName);
            }
        } else {
            compare(arm32Ref, ia32Ref, sysPreferAbiName, &retAbiName);
        }
    } while (false);
    int ret = getAbiIndex(retAbiName);
    ALOGI("selected abi %s(%d) for %s", retAbiName, ret, mpkgName);
    return ret;
}

}  // namespace android
