/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
#ifndef MEDIASYNC_NW_H
#define MEDIASYNC_NW_H

#include <Network/Network.h>
#include <dispatch/dispatch.h>
#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

/**
 * Called once per receive with a copy of the content. `bytes` is valid only during the call.
 */
typedef void (*ms_receive_callback)(void *context, const void *bytes, size_t length, int complete,
                                    int has_error, int error_code);

/**
 * nw_connection_receive for Kotlin/Native. Network.framework passes the content context to the completion, and on
 * cancel that is a default context object (NW_CONNECTION_DEFAULT_*), which Kotlin/Native cannot convert: its bridge
 * aborts the process. This wrapper takes the context itself and forwards only plain values, so no Objective-C object
 * reaches Kotlin.
 */
static inline void ms_connection_receive(nw_connection_t connection, uint32_t minimum, uint32_t maximum,
                                         void *context, ms_receive_callback callback) {
    nw_connection_receive(connection, minimum, maximum,
                          ^(dispatch_data_t content, nw_content_context_t ctx, bool is_complete, nw_error_t error) {
        (void)ctx;
        size_t size = content != NULL ? dispatch_data_get_size(content) : 0;
        char *copy = NULL;
        if (size > 0) {
            copy = (char *)malloc(size);
            if (copy == NULL) {
                // Report the loss as an error: a silent drop would corrupt the stream.
                callback(context, NULL, 0, 0, 1, ENOMEM);
                return;
            }
            dispatch_data_apply(content, ^bool(dispatch_data_t region, size_t offset, const void *buffer, size_t count) {
                (void)region;
                memcpy(copy + offset, buffer, count);
                return true;
            });
        }
        callback(context, copy, size, is_complete ? 1 : 0, error != NULL ? 1 : 0,
                 error != NULL ? nw_error_get_error_code(error) : 0);
        free(copy);
    });
}

#endif /* MEDIASYNC_NW_H */
