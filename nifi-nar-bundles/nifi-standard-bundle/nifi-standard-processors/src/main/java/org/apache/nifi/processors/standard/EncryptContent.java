/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.processors.standard;

import java.security.Security;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.commons.codec.binary.Hex;
import org.apache.nifi.annotation.behavior.EventDriven;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.InputRequirement.Requirement;
import org.apache.nifi.annotation.behavior.SideEffectFree;
import org.apache.nifi.annotation.behavior.SupportsBatching;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.ValidationContext;
import org.apache.nifi.components.ValidationResult;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.flowfile.attributes.CoreAttributes;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.ProcessorInitializationContext;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.io.StreamCallback;
import org.apache.nifi.processors.standard.util.crypto.EncryptProcessorUtils;
import org.apache.nifi.processors.standard.util.crypto.EncryptProcessorUtils.Encryptor;
import org.apache.nifi.processors.standard.util.crypto.KeyedEncryptor;
import org.apache.nifi.processors.standard.util.crypto.OpenPGPKeyBasedEncryptor;
import org.apache.nifi.processors.standard.util.crypto.OpenPGPPasswordBasedEncryptor;
import org.apache.nifi.processors.standard.util.crypto.PasswordBasedEncryptor;
import org.apache.nifi.security.util.EncryptionMethod;
import org.apache.nifi.security.util.KeyDerivationFunction;
import org.apache.nifi.util.StopWatch;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

@EventDriven
@SideEffectFree
@SupportsBatching
@InputRequirement(Requirement.INPUT_REQUIRED)
@Tags({"encryption", "decryption", "password", "JCE", "OpenPGP", "PGP", "GPG"})
@CapabilityDescription("Encrypts or Decrypts a FlowFile using either symmetric encryption with a password and randomly generated salt, or asymmetric encryption using a public and secret key.")
public class EncryptContent extends AbstractProcessor {

    public static final String ENCRYPT_MODE = "Encrypt";
    public static final String DECRYPT_MODE = "Decrypt";

    private static final String WEAK_CRYPTO_ALLOWED_NAME = "allowed";
    private static final String WEAK_CRYPTO_NOT_ALLOWED_NAME = "not-allowed";

    // PropertyDescriptors defined in EncryptProcessorUtils
    static final PropertyDescriptor MODE = copy(EncryptProcessorUtils.MODE);
    static final PropertyDescriptor KEY_DERIVATION_FUNCTION = copy(EncryptProcessorUtils.KEY_DERIVATION_FUNCTION);
    static final PropertyDescriptor ENCRYPTION_ALGORITHM = copy(EncryptProcessorUtils.ENCRYPTION_ALGORITHM);
    static final PropertyDescriptor PASSWORD = copy(EncryptProcessorUtils.PASSWORD);
    static final PropertyDescriptor PUBLIC_KEYRING = copy(EncryptProcessorUtils.PUBLIC_KEYRING);
    static final PropertyDescriptor PUBLIC_KEY_USERID = copy(EncryptProcessorUtils.PUBLIC_KEY_USERID);
    static final PropertyDescriptor PRIVATE_KEYRING = copy(EncryptProcessorUtils.PRIVATE_KEYRING);
    static final PropertyDescriptor PRIVATE_KEYRING_PASSPHRASE = copy(EncryptProcessorUtils.PRIVATE_KEYRING_PASSPHRASE);
    static final PropertyDescriptor RAW_KEY_HEX = copy(EncryptProcessorUtils.RAW_KEY_HEX);
    static final PropertyDescriptor ALLOW_WEAK_CRYPTO = copy(EncryptProcessorUtils.ALLOW_WEAK_CRYPTO);


    public static final Relationship REL_SUCCESS = new Relationship.Builder().name("success")
            .description("Any FlowFile that is successfully encrypted or decrypted will be routed to success").build();

    public static final Relationship REL_FAILURE = new Relationship.Builder().name("failure")
            .description("Any FlowFile that cannot be encrypted or decrypted will be routed to failure").build();
    private List<PropertyDescriptor> properties;

    private Set<Relationship> relationships;

    static {
        // add BouncyCastle encryption providers
        Security.addProvider(new BouncyCastleProvider());
    }

    private static PropertyDescriptor copy(final PropertyDescriptor original) {
        return new PropertyDescriptor.Builder().fromPropertyDescriptor(original).build();
    }

    @Override
    protected void init(final ProcessorInitializationContext context) {
        final List<PropertyDescriptor> properties = new ArrayList<>();
        properties.add(MODE);
        properties.add(KEY_DERIVATION_FUNCTION);
        properties.add(ENCRYPTION_ALGORITHM);
        properties.add(ALLOW_WEAK_CRYPTO);
        properties.add(PASSWORD);
        properties.add(RAW_KEY_HEX);
        properties.add(PUBLIC_KEYRING);
        properties.add(PUBLIC_KEY_USERID);
        properties.add(PRIVATE_KEYRING);
        properties.add(PRIVATE_KEYRING_PASSPHRASE);
        this.properties = Collections.unmodifiableList(properties);

        final Set<Relationship> relationships = new HashSet<>();
        relationships.add(REL_SUCCESS);
        relationships.add(REL_FAILURE);
        this.relationships = Collections.unmodifiableSet(relationships);
    }

    @Override
    public Set<Relationship> getRelationships() {
        return relationships;
    }

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return properties;
    }

    @Override
    protected Collection<ValidationResult> customValidate(final ValidationContext context) {
        final List<ValidationResult> validationResults = new ArrayList<>(super.customValidate(context));
        return EncryptProcessorUtils.standardValidate(context, validationResults);
    }

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) {
        FlowFile flowFile = session.get();
        if (flowFile == null) {
            return;
        }

        final ComponentLog logger = getLogger();
        final String method = context.getProperty(ENCRYPTION_ALGORITHM).getValue();
        final EncryptionMethod encryptionMethod = EncryptionMethod.valueOf(method);
        final String providerName = encryptionMethod.getProvider();
        final String algorithm = encryptionMethod.getAlgorithm();
        final String password = context.getProperty(PASSWORD).getValue();
        final KeyDerivationFunction kdf = KeyDerivationFunction.valueOf(context.getProperty(KEY_DERIVATION_FUNCTION).getValue());
        final boolean encrypt = context.getProperty(MODE).getValue().equalsIgnoreCase(ENCRYPT_MODE);

        Encryptor encryptor;
        StreamCallback callback;
        try {
            if (EncryptProcessorUtils.isPGPAlgorithm(algorithm)) {
                final String filename = flowFile.getAttribute(CoreAttributes.FILENAME.key());
                final String publicKeyring = context.getProperty(PUBLIC_KEYRING).getValue();
                final String privateKeyring = context.getProperty(PRIVATE_KEYRING).getValue();
                if (encrypt && publicKeyring != null) {
                    final String publicUserId = context.getProperty(PUBLIC_KEY_USERID).getValue();
                    encryptor = new OpenPGPKeyBasedEncryptor(algorithm, providerName, publicKeyring, publicUserId, null, filename);
                } else if (!encrypt && privateKeyring != null) {
                    final char[] keyringPassphrase = context.getProperty(PRIVATE_KEYRING_PASSPHRASE).getValue().toCharArray();
                    encryptor = new OpenPGPKeyBasedEncryptor(algorithm, providerName, privateKeyring, null, keyringPassphrase,
                            filename);
                } else {
                    final char[] passphrase = Normalizer.normalize(password, Normalizer.Form.NFC).toCharArray();
                    encryptor = new OpenPGPPasswordBasedEncryptor(algorithm, providerName, passphrase, filename);
                }
            } else if (kdf.equals(KeyDerivationFunction.NONE)) { // Raw key
                final String keyHex = context.getProperty(RAW_KEY_HEX).getValue();
                encryptor = new KeyedEncryptor(encryptionMethod, Hex.decodeHex(keyHex.toCharArray()));
            } else { // PBE
                final char[] passphrase = Normalizer.normalize(password, Normalizer.Form.NFC).toCharArray();
                encryptor = new PasswordBasedEncryptor(encryptionMethod, passphrase, kdf);
            }

            if (encrypt) {
                callback = encryptor.getEncryptionCallback();
            } else {
                callback = encryptor.getDecryptionCallback();
            }

        } catch (final Exception e) {
            logger.error("Failed to initialize {}cryption algorithm because - ", new Object[]{encrypt ? "en" : "de", e});
            session.rollback();
            context.yield();
            return;
        }

        try {
            final StopWatch stopWatch = new StopWatch(true);
            flowFile = session.write(flowFile, callback);
            logger.info("successfully {}crypted {}", new Object[]{encrypt ? "en" : "de", flowFile});
            session.getProvenanceReporter().modifyContent(flowFile, stopWatch.getElapsed(TimeUnit.MILLISECONDS));
            session.transfer(flowFile, REL_SUCCESS);
        } catch (final ProcessException e) {
            logger.error("Cannot {}crypt {} - ", new Object[]{encrypt ? "en" : "de", flowFile, e});
            session.transfer(flowFile, REL_FAILURE);
        }
    }

}