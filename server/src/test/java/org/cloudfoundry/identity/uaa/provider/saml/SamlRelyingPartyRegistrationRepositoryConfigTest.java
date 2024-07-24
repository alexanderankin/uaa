package org.cloudfoundry.identity.uaa.provider.saml;

import org.bouncycastle.jcajce.provider.BouncyCastleFipsProvider;
import org.cloudfoundry.identity.uaa.util.KeyWithCert;
import org.cloudfoundry.identity.uaa.util.KeyWithCertTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.web.RelyingPartyRegistrationResolver;

import java.security.Security;
import java.security.cert.CertificateException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SamlRelyingPartyRegistrationRepositoryConfigTest {
    private static final String KEY = KeyWithCertTest.encryptedKey;
    private static final String PASSPHRASE = KeyWithCertTest.password;
    private static final String CERT = KeyWithCertTest.goodCert;
    private static final String ENTITY_ID = "entityId";
    private static final String NAME_ID = "nameIdFormat";

    @Mock
    SamlConfigProps samlConfigProps;

    @Mock
    BootstrapSamlIdentityProviderData bootstrapSamlIdentityProviderData;

    @Mock
    SamlIdentityProviderConfigurator samlIdentityProviderConfigurator;

    @BeforeAll
    public static void addProvider() {
        Security.addProvider(new BouncyCastleFipsProvider());
    }

    @BeforeEach
    public void setup() throws CertificateException {
        KeyWithCert keyWithCert = new KeyWithCert(KEY, PASSPHRASE, CERT);
        when(samlConfigProps.getKeysWithCerts()).thenReturn(List.of(keyWithCert));
    }

    @Test
    void relyingPartyRegistrationRepository() {
        SamlRelyingPartyRegistrationRepositoryConfig config = new SamlRelyingPartyRegistrationRepositoryConfig(ENTITY_ID, samlConfigProps, bootstrapSamlIdentityProviderData, NAME_ID);
        RelyingPartyRegistrationRepository repository = config.relyingPartyRegistrationRepository(samlIdentityProviderConfigurator);
        assertThat(repository).isNotNull();
    }

    @Test
    void relyingPartyRegistrationResolver() {
        SamlRelyingPartyRegistrationRepositoryConfig config = new SamlRelyingPartyRegistrationRepositoryConfig(ENTITY_ID, samlConfigProps, bootstrapSamlIdentityProviderData, NAME_ID);
        RelyingPartyRegistrationRepository repository = config.relyingPartyRegistrationRepository(samlIdentityProviderConfigurator);
        RelyingPartyRegistrationResolver resolver = config.relyingPartyRegistrationResolver(repository);

        assertThat(resolver).isNotNull();
    }

    @Test
    void buildsRegistrationForExample() {
        SamlRelyingPartyRegistrationRepositoryConfig config = new SamlRelyingPartyRegistrationRepositoryConfig(ENTITY_ID, samlConfigProps, bootstrapSamlIdentityProviderData, NAME_ID);
        RelyingPartyRegistrationRepository repository = config.relyingPartyRegistrationRepository(samlIdentityProviderConfigurator);
        RelyingPartyRegistration registration = repository.findByRegistrationId("example");
        assertThat(registration)
                .returns("example", RelyingPartyRegistration::getRegistrationId)
                .returns(ENTITY_ID, RelyingPartyRegistration::getEntityId)
                .returns(NAME_ID, RelyingPartyRegistration::getNameIdFormat)
                // from functions
                .returns("{baseUrl}/saml/SSO/alias/entityId", RelyingPartyRegistration::getAssertionConsumerServiceLocation)
                .returns("{baseUrl}/saml/SingleLogout/alias/entityId", RelyingPartyRegistration::getSingleLogoutServiceResponseLocation)
                // from xml
                .extracting(RelyingPartyRegistration::getAssertingPartyDetails)
                .returns("exampleEntityId", RelyingPartyRegistration.AssertingPartyDetails::getEntityId);
    }
}
