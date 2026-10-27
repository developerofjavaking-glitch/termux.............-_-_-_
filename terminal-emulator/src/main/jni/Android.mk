LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE:= libtermux
LOCAL_SRC_FILES:= termux.c
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE:= libboomaux-redirect
LOCAL_SRC_FILES:= redirect.c
LOCAL_LDLIBS:= -ldl
include $(BUILD_SHARED_LIBRARY)
