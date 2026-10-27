GeoKlik Android HTTPS fallback trust resource.

The production host remains restricted to HTTPS and Android hostname/certificate validation.
The bundled CA resource is intended only as an additional trust anchor source for geoklik.philmech.gov.ph on devices with an outdated Android CA store.

Do not replace this mechanism with a trust-all TrustManager or permissive HostnameVerifier.
