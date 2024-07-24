package org.cloudfoundry.identity.uaa.provider.saml;

import org.cloudfoundry.identity.uaa.zone.IdentityZone;
import org.cloudfoundry.identity.uaa.zone.IdentityZoneConfiguration;
import org.cloudfoundry.identity.uaa.zone.SamlConfig;
import org.cloudfoundry.identity.uaa.zone.beans.IdentityZoneManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.registration.Saml2MessageBinding;
import org.springframework.security.saml2.provider.service.web.RelyingPartyRegistrationResolver;
import org.xmlunit.assertj.XmlAssert;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cloudfoundry.identity.uaa.provider.saml.Saml2TestUtils.xmlNamespaces;
import static org.cloudfoundry.identity.uaa.provider.saml.SamlNameIdFormats.NAMEID_FORMAT_EMAIL;
import static org.cloudfoundry.identity.uaa.provider.saml.SamlNameIdFormats.NAMEID_FORMAT_PERSISTENT;
import static org.cloudfoundry.identity.uaa.provider.saml.SamlNameIdFormats.NAMEID_FORMAT_TRANSIENT;
import static org.cloudfoundry.identity.uaa.provider.saml.SamlNameIdFormats.NAMEID_FORMAT_UNSPECIFIED;
import static org.cloudfoundry.identity.uaa.provider.saml.SamlNameIdFormats.NAMEID_FORMAT_X509SUBJECT;
import static org.cloudfoundry.identity.uaa.provider.saml.TestSaml2X509Credentials.relyingPartySigningCredential;
import static org.cloudfoundry.identity.uaa.provider.saml.TestSaml2X509Credentials.relyingPartyVerifyingCredential;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SamlMetadataEndpointTest {
    private static final String ASSERTION_CONSUMER_SERVICE = "{baseUrl}/saml/SSO/alias/";
    private static final String REGISTRATION_ID = "regId";
    private static final String ENTITY_ID = "entityId";
    private static final String ZONE_ENTITY_ID = "zoneEntityId";
    private static final String TEST_ZONE = "testzone1";

    SamlMetadataEndpoint endpoint;

    @Mock
    RelyingPartyRegistrationResolver resolver;
    @Mock
    IdentityZoneManager identityZoneManager;
    @Mock
    RelyingPartyRegistration registration;
    @Mock
    IdentityZone identityZone;
    @Mock
    IdentityZoneConfiguration identityZoneConfiguration;
    @Mock
    SamlConfig samlConfig;

    MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        endpoint = spy(new SamlMetadataEndpoint(resolver, identityZoneManager));
        when(registration.getEntityId()).thenReturn(ENTITY_ID);
        when(registration.getSigningX509Credentials()).thenReturn(List.of(relyingPartySigningCredential()));
        when(registration.getDecryptionX509Credentials()).thenReturn(List.of(relyingPartyVerifyingCredential()));
        when(registration.getAssertionConsumerServiceBinding()).thenReturn(Saml2MessageBinding.REDIRECT);
        when(registration.getAssertionConsumerServiceLocation()).thenReturn(ASSERTION_CONSUMER_SERVICE);
        when(identityZoneManager.getCurrentIdentityZone()).thenReturn(identityZone);
        when(identityZone.getConfig()).thenReturn(identityZoneConfiguration);
        when(identityZoneConfiguration.getSamlConfig()).thenReturn(samlConfig);
    }

    @Test
    void testDefaultFileName() {
        when(resolver.resolve(request, REGISTRATION_ID)).thenReturn(registration);

        ResponseEntity<String> response = endpoint.metadataEndpoint(request, REGISTRATION_ID);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"saml-sp.xml\"; filename*=UTF-8''saml-sp.xml");
    }

    @Test
    void testZonedFileName() {
        when(resolver.resolve(request, REGISTRATION_ID)).thenReturn(registration);
        when(identityZone.isUaa()).thenReturn(false);
        when(identityZone.getSubdomain()).thenReturn(TEST_ZONE);
        when(endpoint.retrieveZone()).thenReturn(identityZone);

        ResponseEntity<String> response = endpoint.metadataEndpoint(request, REGISTRATION_ID);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"saml-%1$s-sp.xml\"; filename*=UTF-8''saml-%1$s-sp.xml".formatted(TEST_ZONE));
    }

    @Test
    void testDefaultMetadataXml() {
        when(resolver.resolve(request, REGISTRATION_ID)).thenReturn(registration);
        when(samlConfig.isWantAssertionSigned()).thenReturn(true);
        when(samlConfig.isRequestSigned()).thenReturn(true);

        ResponseEntity<String> response = endpoint.metadataEndpoint(request, REGISTRATION_ID);
        XmlAssert xmlAssert = XmlAssert.assertThat(response.getBody()).withNamespaceContext(xmlNamespaces());
        xmlAssert.valueByXPath("//md:EntityDescriptor/@entityID").isEqualTo(ENTITY_ID);
        xmlAssert.valueByXPath("//md:EntityDescriptor/@ID").isEqualTo(ENTITY_ID);
        xmlAssert.valueByXPath("//md:SPSSODescriptor/@AuthnRequestsSigned").isEqualTo(true);
        xmlAssert.valueByXPath("//md:SPSSODescriptor/@WantAssertionsSigned").isEqualTo(true);
        xmlAssert.nodesByXPath("//md:AssertionConsumerService")
                .extractingAttribute("Location")
                .containsExactly(ASSERTION_CONSUMER_SERVICE);
        xmlAssert.nodesByXPath("//md:NameIDFormat")
                .extractingText()
                .containsExactlyInAnyOrder(NAMEID_FORMAT_EMAIL, NAMEID_FORMAT_PERSISTENT,
                        NAMEID_FORMAT_TRANSIENT, NAMEID_FORMAT_UNSPECIFIED, NAMEID_FORMAT_X509SUBJECT);
    }

    @Test
    void testDefaultMetadataXml_alternateValues() {
        when(resolver.resolve(request, REGISTRATION_ID)).thenReturn(registration);
        when(samlConfig.isWantAssertionSigned()).thenReturn(false);
        when(samlConfig.isRequestSigned()).thenReturn(false);

        ResponseEntity<String> response = endpoint.metadataEndpoint(request, REGISTRATION_ID);
        XmlAssert xmlAssert = XmlAssert.assertThat(response.getBody()).withNamespaceContext(xmlNamespaces());
        xmlAssert.valueByXPath("//md:SPSSODescriptor/@AuthnRequestsSigned").isEqualTo(false);
        xmlAssert.valueByXPath("//md:SPSSODescriptor/@WantAssertionsSigned").isEqualTo(false);
    }
}
