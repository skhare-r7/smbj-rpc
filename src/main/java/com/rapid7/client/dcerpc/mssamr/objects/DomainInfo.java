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
package com.rapid7.client.dcerpc.mssamr.objects;

import com.rapid7.client.dcerpc.io.ndr.Unmarshallable;

/**
 * This class represents the structure returned with {@link EnumeratedDomains}.
 *
 * <pre>
 * Relative ID (idx)  - index
 * RPC_UNICODE_STRING - Domain Name
 * </pre>
 *
 * @see <a href="https://msdn.microsoft.com/en-us/library/cc245560.aspx">
 *       https://msdn.microsoft.com/en-us/library/cc245560.aspx</a>
 */
public class DomainInfo extends SAMPRRIDEnumeration implements Unmarshallable {

}
