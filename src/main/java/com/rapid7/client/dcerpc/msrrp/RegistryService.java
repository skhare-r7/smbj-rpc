/**
 * Copyright 2017, Rapid7, Inc.
 *
 * License: BSD-3-clause
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * * Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *
 * * Neither the name of the copyright holder nor the names of its contributors
 * may be used to endorse or promote products derived from this software
 * without specific prior written permission.
 */
package com.rapid7.client.dcerpc.msrrp;

import static com.rapid7.client.dcerpc.mserref.SystemErrorCode.ERROR_NO_MORE_ITEMS;
import static com.rapid7.client.dcerpc.mserref.SystemErrorCode.ERROR_SUCCESS;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.google.common.base.Strings;
import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.protocol.commons.EnumWithValue.EnumUtils;
import com.rapid7.client.dcerpc.RPCException;
import com.rapid7.client.dcerpc.messages.HandleResponse;
import com.rapid7.client.dcerpc.msrrp.messages.BaseRegEnumKeyRequest;
import com.rapid7.client.dcerpc.msrrp.messages.BaseRegEnumKeyResponse;
import com.rapid7.client.dcerpc.msrrp.messages.BaseRegEnumValueRequest;
import com.rapid7.client.dcerpc.msrrp.messages.BaseRegEnumValueResponse;
import com.rapid7.client.dcerpc.msrrp.messages.BaseRegGetKeySecurityRequest;
import com.rapid7.client.dcerpc.msrrp.messages.BaseRegGetKeySecurityResponse;
import com.rapid7.client.dcerpc.msrrp.messages.BaseRegOpenKey;
import com.rapid7.client.dcerpc.msrrp.messages.BaseRegQueryInfoKeyRequest;
import com.rapid7.client.dcerpc.msrrp.messages.BaseRegQueryInfoKeyResponse;
import com.rapid7.client.dcerpc.msrrp.messages.BaseRegQueryValueRequest;
import com.rapid7.client.dcerpc.msrrp.messages.BaseRegQueryValueResponse;
import com.rapid7.client.dcerpc.msrrp.messages.HandleRequest;
import com.rapid7.client.dcerpc.objects.FileTime;
import com.rapid7.client.dcerpc.service.Service;
import com.rapid7.client.dcerpc.transport.RPCTransport;

/**
 * This class implements a partial registry service in accordance with [MS-RRP]: Windows Remote Registry Protocol which
 * specifies the Windows Remote Registry Protocol, a remote procedure call (RPC)-based client/server protocol that is
 * used to remotely manage a hierarchical data store such as the Windows registry.
 *
 * @see <a href="https://msdn.microsoft.com/en-us/library/cc244877.aspx">[MS-RRP]: Windows Remote Registry Protocol</a>
 */
public class RegistryService extends Service {
    private final static int MAX_REGISTRY_KEY_NAME_SIZE = 32767;
    private final static int MAX_REGISTRY_KEY_CLASS_SIZE = 32767;
    private final static int MAX_REGISTRY_VALUE_NAME_SIZE = 32767;
    private final static int MAX_REGISTRY_VALUE_DATA_SIZE = 1048576;
    private final static EnumSet<AccessMask> ACCESS_MASK = EnumSet.of(AccessMask.MAXIMUM_ALLOWED);
    private final Map<RegistryHive, byte[]> hiveCache = new HashMap<>();
    private final Map<RegistryHandleKey, byte[]> keyPathCache = new HashMap<>();

    public RegistryService(final RPCTransport transport) {
        super(transport);
    }

    public boolean doesKeyExist(final String hiveName, final String keyPath) throws IOException {
        try {
            openKey(hiveName, keyPath);
        } catch (final RPCException exception) {
            if (exception.hasErrorCode()) {
                switch (exception.getErrorCode()) {
                    case ERROR_FILE_NOT_FOUND:
                        return false;
                    default:
                        throw exception;
                }
            }
        }
        return true;
    }

    public boolean doesValueExist(final String hiveName, final String keyPath, final String valueName)
            throws IOException {
        try {
            getValue(hiveName, keyPath, valueName);
        } catch (final RPCException exception) {
            if (exception.hasErrorCode()) {
                switch (exception.getErrorCode()) {
                    case ERROR_FILE_NOT_FOUND:
                        return false;
                    default:
                        throw exception;
                }
            }
        }
        return true;
    }

    public RegistryKeyInfo getKeyInfo(final String hiveName, final String keyPath) throws IOException {
        final byte[] handle = openKey(hiveName, keyPath);
        final BaseRegQueryInfoKeyRequest request = new BaseRegQueryInfoKeyRequest(handle);
        final BaseRegQueryInfoKeyResponse response = callExpectSuccess(request, "BaseRegQueryInfoKey");
        return new RegistryKeyInfo(response.getSubKeys(), response.getMaxSubKeyLen(), response.getMaxClassLen(), response.getValues(), response.getMaxValueNameLen(), response.getMaxValueLen(), response.getSecurityDescriptor(), response.getLastWriteTime());
    }

    public List<RegistryKey> getSubKeys(final String hiveName, final String keyPath) throws IOException {
        final List<RegistryKey> keyNames = new LinkedList<>();
        final byte[] handle = openKey(hiveName, keyPath);
        for (int index = 0; ; index++) {
            final BaseRegEnumKeyRequest request = new BaseRegEnumKeyRequest(handle, index, MAX_REGISTRY_KEY_NAME_SIZE, MAX_REGISTRY_KEY_CLASS_SIZE);
            final BaseRegEnumKeyResponse response = call(request);
            final int returnCode = response.getReturnValue();

            if (ERROR_SUCCESS.is(returnCode)) {
                keyNames.add(new RegistryKey(response.getName(), new FileTime(response.getLastWriteTime())));
            } else if (ERROR_NO_MORE_ITEMS.is(returnCode)) {
                return Collections.unmodifiableList(new ArrayList<>(keyNames));
            } else {
                throw new RPCException("BaseRegEnumKey", returnCode);
            }
        }
    }

    public List<RegistryValue> getValues(final String hiveName, final String keyPath) throws IOException {
        final List<RegistryValue> values = new LinkedList<>();
        final byte[] handle = openKey(hiveName, keyPath);
        for (int index = 0; ; index++) {
            final BaseRegEnumValueRequest request = new BaseRegEnumValueRequest(handle, index, MAX_REGISTRY_VALUE_NAME_SIZE, MAX_REGISTRY_VALUE_DATA_SIZE);
            final BaseRegEnumValueResponse response = call(request);
            final int returnCode = response.getReturnValue();

            if (ERROR_SUCCESS.is(returnCode)) {
                values.add(new RegistryValue(response.getName(), response.getType(), response.getData()));
            } else if (ERROR_NO_MORE_ITEMS.is(returnCode)) {
                return Collections.unmodifiableList(new ArrayList<>(values));
            } else {
                throw new RPCException("BaseRegEnumValue", returnCode);
            }
        }
    }

    public RegistryValue getValue(final String hiveName, final String keyPath, final String valueName)
            throws IOException {
        final String canonicalizedValueName = Strings.nullToEmpty(valueName);
        final byte[] handle = openKey(hiveName, keyPath);
        final BaseRegQueryValueRequest request = new BaseRegQueryValueRequest(handle, canonicalizedValueName, MAX_REGISTRY_VALUE_DATA_SIZE);
        final BaseRegQueryValueResponse response = callExpectSuccess(request, "BaseRegQueryValue");
        return new RegistryValue(canonicalizedValueName, response.getType(), response.getData());
    }

    public byte[] getKeySecurity(final String hiveName, final String keyPath, final int securityDescriptorType)
            throws IOException {
        final byte[] handle = openKey(hiveName, keyPath,
            (int) EnumUtils.toLong(EnumSet.of(AccessMask.MAXIMUM_ALLOWED, AccessMask.ACCESS_SYSTEM_SECURITY)));
        final int size = getKeyInfo(hiveName, keyPath).getSecurityDescriptor();
        final BaseRegGetKeySecurityRequest request = new BaseRegGetKeySecurityRequest(handle, securityDescriptorType,
            size);
        final BaseRegGetKeySecurityResponse response = callExpectSuccess(request, "BaseRegGetKeySecurity");
        return response.getRawSecurityDescriptor();
    }

    protected String canonicalize(String keyPath) {
        keyPath = Strings.nullToEmpty(keyPath);
        keyPath = keyPath.toLowerCase();
        while (keyPath.contains("\\\\")) {
            keyPath = keyPath.replace("\\\\", "\\");
        }
        if (keyPath.endsWith("\\")) {
            keyPath = keyPath.substring(0, keyPath.length() - 1);
        }
        return keyPath;
    }

    protected byte[] openHive(final String hiveName) throws IOException {
        if (hiveName == null) {
            throw new IllegalArgumentException("Invalid hive: " + hiveName);
        }
        final RegistryHive hive = RegistryHive.getRegistryHiveByName(hiveName);
        if (hive == null) {
            throw new IllegalArgumentException("Unknown hive: " + hiveName);
        }
        synchronized (hiveCache) {
            if (hiveCache.containsKey(hive)) {
                return hiveCache.get(hive);
            } else {
                final short opNum = hive.getOpNum();
                final HandleRequest request = new HandleRequest(opNum, ACCESS_MASK);
                final HandleResponse response = callExpectSuccess(request, hive.getOpName());
                final byte[] handle = response.getHandle();
                hiveCache.put(hive, handle);
                return handle;
            }
        }
    }

    protected byte[] openKey(final String hiveName, final String keyPath) throws IOException {
        return openKey(hiveName, keyPath, (int) AccessMask.MAXIMUM_ALLOWED.getValue());
    }

    protected byte[] openKey(final String hiveName, final String keyPath, int desiredAccess)
            throws IOException {
        final String canonicalizedKeyPath = canonicalize(keyPath);
        if (canonicalizedKeyPath.isEmpty()) {
            return openHive(hiveName);
        }
        synchronized (keyPathCache) {
            final RegistryHandleKey cachingKey = new RegistryHandleKey(canonicalizedKeyPath, desiredAccess);
            if (keyPathCache.containsKey(cachingKey)) {
                return keyPathCache.get(cachingKey);
            }
            final byte[] hiveHandle = openHive(hiveName);
            final BaseRegOpenKey request = new BaseRegOpenKey(hiveHandle, canonicalizedKeyPath, 0, desiredAccess);
            final HandleResponse response = callExpectSuccess(request, "BaseRegOpenKey");
            final byte[] keyHandle = response.getHandle();
            keyPathCache.put(cachingKey, keyHandle);
            return keyHandle;
        }
    }

    private static class RegistryHandleKey {
        private final String path;
        private final int access;

        RegistryHandleKey(final String path, final int access) {
            this.path = Objects.requireNonNull(path);
            this.access = access;
        }

        @Override
        public boolean equals(final Object o) {
            if (!(o instanceof RegistryHandleKey))
                return false;
            final RegistryHandleKey other = (RegistryHandleKey) o;
            return path.equals(other.path) && access == other.access;
        }

        @Override
        public int hashCode() {
            return Objects.hash(path, access);
        }
    }
}
