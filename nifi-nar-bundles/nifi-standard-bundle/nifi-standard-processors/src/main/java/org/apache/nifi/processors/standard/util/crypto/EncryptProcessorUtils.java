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
package org.apache.nifi.processors.standard.util.crypto;


import java.util.Collection;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.components.AllowableValue;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.ValidationContext;
import org.apache.nifi.components.ValidationResult;
import org.apache.nifi.processor.io.StreamCallback;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.security.util.EncryptionMethod;
import org.apache.nifi.security.util.KeyDerivationFunction;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class EncryptProcessorUtils {

    public static final String ENCRYPT_MODE = "Encrypt";
    public static final String DECRYPT_MODE = "Decrypt";

    public static final String ALLOW_WEAK_CRYPTO_PD_NAME = "allow-weak-crypto";
    public static final String MODE_PD_NAME = "mode";
    public static final String WEAK_CRYPTO_ALLOWED_NAME = "allowed";
    public static final String WEAK_CRYPTO_NOT_ALLOWED_NAME = "not-allowed";
    public static final String PUBLIC_KEYRING_PD_NAME = "public-keyring-file";
    public static final String PUBLIC_KEY_USERID_PD_NAME = "public-key-user-id";
    public static final String PRIVATE_KEYRING_PD_NAME = "private-keyring-file";
    public static final String PRIVATE_KEYRING_PASSPHRASE_PD_NAME = "private-keyring-passphrase";
    public static final String RAW_KEY_HEX_PD_NAME = "raw-key-hex";
    public static final String PASSWORD_PD_NAME = "password";
    public static final String ENCRYPTION_ALGORITHM_PD_NAME = "encryption-algorithm";
    public static final String KEY_DERIVATION_FUNCTION_PD_NAME = "key-derivation-function";

    public static final PropertyDescriptor MODE = new PropertyDescriptor.Builder()
            .name(MODE_PD_NAME)
            .displayName("Mode")
            .description("Specifies whether the content should be encrypted or decrypted")
            .required(true)
            .allowableValues(ENCRYPT_MODE, DECRYPT_MODE)
            .defaultValue(ENCRYPT_MODE)
            .build();
    public static final PropertyDescriptor KEY_DERIVATION_FUNCTION = new PropertyDescriptor.Builder()
            .name(KEY_DERIVATION_FUNCTION_PD_NAME)
            .displayName("Key Derivation Function")
            .description("Specifies the key derivation function to generate the key from the password (and salt)")
            .required(true)
            .allowableValues(EncryptProcessorUtils.buildKeyDerivationFunctionAllowableValues())
            .defaultValue(KeyDerivationFunction.OPENSSL_EVP_BYTES_TO_KEY.name())
            .build();
    public static final PropertyDescriptor ENCRYPTION_ALGORITHM = new PropertyDescriptor.Builder()
            .name(ENCRYPTION_ALGORITHM_PD_NAME)
            .displayName("Encryption Algorithm")
            .description("The Encryption Algorithm to use")
            .required(true)
            .allowableValues(EncryptProcessorUtils.buildEncryptionMethodAllowableValues())
            .defaultValue(EncryptionMethod.MD5_128AES.name())
            .build();
    public static final PropertyDescriptor PASSWORD = new PropertyDescriptor.Builder()
            .name(PASSWORD_PD_NAME)
            .displayName("Password")
            .description("The Password to use for encrypting or decrypting the data")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .sensitive(true)
            .build();
    public static final PropertyDescriptor PUBLIC_KEYRING = new PropertyDescriptor.Builder()
            .name(PUBLIC_KEYRING_PD_NAME)
            .displayName("Public Keyring File")
            .description("In a PGP encrypt mode, this keyring contains the public key of the recipient")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();
    public static final PropertyDescriptor PUBLIC_KEY_USERID = new PropertyDescriptor.Builder()
            .name(PUBLIC_KEY_USERID_PD_NAME)
            .displayName("Public Key User Id")
            .description("In a PGP encrypt mode, this user id of the recipient")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();
    public static final PropertyDescriptor PRIVATE_KEYRING = new PropertyDescriptor.Builder()
            .name(PRIVATE_KEYRING_PD_NAME)
            .displayName("Private Keyring File")
            .description("In a PGP decrypt mode, this keyring contains the private key of the recipient")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();
    public static final PropertyDescriptor PRIVATE_KEYRING_PASSPHRASE = new PropertyDescriptor.Builder()
            .name(PRIVATE_KEYRING_PASSPHRASE_PD_NAME)
            .displayName("Private Keyring Passphrase")
            .description("In a PGP decrypt mode, this is the private keyring passphrase")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .sensitive(true)
            .build();
    public static final PropertyDescriptor RAW_KEY_HEX = new PropertyDescriptor.Builder()
            .name(RAW_KEY_HEX_PD_NAME)
            .displayName("Raw Key (hexadecimal)")
            .description("In keyed encryption, this is the raw key, encoded in hexadecimal")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .sensitive(true)
            .build();
    public static final PropertyDescriptor ALLOW_WEAK_CRYPTO = new PropertyDescriptor.Builder()
            .name(ALLOW_WEAK_CRYPTO_PD_NAME)
            .displayName("Allow insecure cryptographic modes")
            .description("Overrides the default behavior to prevent unsafe combinations of encryption algorithms and short passwords on JVMs with limited strength cryptographic jurisdiction policies")
            .required(true)
            .allowableValues(buildWeakCryptoAllowableValues())
            .defaultValue(buildDefaultWeakCryptoAllowableValue().getValue())
            .build();

    public static boolean isPGPAlgorithm(final String algorithm) {
        return algorithm.startsWith("PGP");
    }

    public static boolean isPGPArmoredAlgorithm(final String algorithm) {
        return isPGPAlgorithm(algorithm) && algorithm.endsWith("ASCII-ARMOR");
    }


    public static AllowableValue[] buildKeyDerivationFunctionAllowableValues() {
        final KeyDerivationFunction[] keyDerivationFunctions = KeyDerivationFunction.values();
        List<AllowableValue> allowableValues = new ArrayList<>(keyDerivationFunctions.length);
        for (KeyDerivationFunction kdf : keyDerivationFunctions) {
            allowableValues.add(new AllowableValue(kdf.name(), kdf.getName(), kdf.getDescription()));
        }

        return allowableValues.toArray(new AllowableValue[0]);
    }

    public static AllowableValue[] buildEncryptionMethodAllowableValues() {
        final EncryptionMethod[] encryptionMethods = EncryptionMethod.values();
        List<AllowableValue> allowableValues = new ArrayList<>(encryptionMethods.length);
        for (EncryptionMethod em : encryptionMethods) {
            allowableValues.add(new AllowableValue(em.name(), em.name(), em.toString()));
        }

        return allowableValues.toArray(new AllowableValue[0]);
    }

    public static AllowableValue[] buildWeakCryptoAllowableValues() {
        List<AllowableValue> allowableValues = new ArrayList<>();
        allowableValues.add(new AllowableValue(WEAK_CRYPTO_ALLOWED_NAME, "Allowed", "Operation will not be blocked and no alerts will be presented " +
                "when unsafe combinations of encryption algorithms and passwords are provided"));
        allowableValues.add(buildDefaultWeakCryptoAllowableValue());
        return allowableValues.toArray(new AllowableValue[0]);
    }

    public static AllowableValue buildDefaultWeakCryptoAllowableValue() {
        return new AllowableValue(WEAK_CRYPTO_NOT_ALLOWED_NAME, "Not Allowed", "When set, operation will be blocked and alerts will be presented to the user " +
                "if unsafe combinations of encryption algorithms and passwords are provided on a JVM with limited strength crypto. To fix this, see the Admin Guide.");
    }

    public static List<ValidationResult> validatePGP(EncryptionMethod encryptionMethod, String password, boolean encrypt, String publicKeyring, String publicUserId, String privateKeyring,
                                              String privateKeyringPassphrase) {
        List<ValidationResult> validationResults = new ArrayList<>();

        if (password == null) {
            if (encrypt) {
                // If encrypting without a password, require both public-keyring-file and public-key-user-id
                if (publicKeyring == null || publicUserId == null) {
                    validationResults.add(new ValidationResult.Builder().subject(PUBLIC_KEYRING_PD_NAME)
                            .explanation(encryptionMethod.getAlgorithm() + " encryption without a " + PASSWORD_PD_NAME + " requires both "
                                    + PUBLIC_KEYRING_PD_NAME + " and " + PUBLIC_KEY_USERID_PD_NAME)
                            .build());
                } else {
                    // Verify the public keyring contains the user id
                    try {
                        if (OpenPGPKeyBasedEncryptor.getPublicKey(publicUserId, publicKeyring) == null) {
                            validationResults.add(new ValidationResult.Builder().subject(PUBLIC_KEYRING_PD_NAME)
                                    .explanation(PUBLIC_KEYRING_PD_NAME + " " + publicKeyring
                                            + " does not contain user id " + publicUserId)
                                    .build());
                        }
                    } catch (final Exception e) {
                        validationResults.add(new ValidationResult.Builder().subject(PUBLIC_KEYRING_PD_NAME)
                                .explanation("Invalid " + PUBLIC_KEYRING_PD_NAME + " " + publicKeyring
                                        + " because " + e.toString())
                                .build());
                    }
                }
            } else { // Decrypt
                // Require both private-keyring-file and private-keyring-passphrase
                if (privateKeyring == null || privateKeyringPassphrase == null) {
                    validationResults.add(new ValidationResult.Builder().subject(PRIVATE_KEYRING_PD_NAME)
                            .explanation(encryptionMethod.getAlgorithm() + " decryption without a " + PASSWORD_PD_NAME + " requires both "
                                    + PRIVATE_KEYRING_PD_NAME + " and " + PRIVATE_KEYRING_PASSPHRASE_PD_NAME)
                            .build());
                } else {
                    final String providerName = encryptionMethod.getProvider();
                    // Verify the passphrase works on the private keyring
                    try {
                        if (!OpenPGPKeyBasedEncryptor.validateKeyring(providerName, privateKeyring, privateKeyringPassphrase.toCharArray())) {
                            validationResults.add(new ValidationResult.Builder().subject(PRIVATE_KEYRING_PD_NAME)
                                    .explanation(PRIVATE_KEYRING_PD_NAME + " " + privateKeyring
                                            + " could not be opened with the provided " + PRIVATE_KEYRING_PASSPHRASE_PD_NAME)
                                    .build());
                        }
                    } catch (final Exception e) {
                        validationResults.add(new ValidationResult.Builder().subject(PRIVATE_KEYRING_PD_NAME)
                                .explanation("Invalid " + PRIVATE_KEYRING_PD_NAME + " " + privateKeyring
                                        + " because " + e.toString())
                                .build());
                    }
                }
            }
        }

        return validationResults;
    }

    public static List<ValidationResult> validatePBE(EncryptionMethod encryptionMethod, KeyDerivationFunction kdf, String password, boolean allowWeakCrypto) {
        List<ValidationResult> validationResults = new ArrayList<>();
        boolean limitedStrengthCrypto = !PasswordBasedEncryptor.supportsUnlimitedStrength();

        // Password required (short circuits validation because other conditions depend on password presence)
        if (StringUtils.isEmpty(password)) {
            validationResults.add(new ValidationResult.Builder().subject(PASSWORD_PD_NAME)
                    .explanation(PASSWORD_PD_NAME + " is required when using algorithm " + encryptionMethod.getAlgorithm()).build());
            return validationResults;
        }

        // If weak crypto is not explicitly allowed via override, check the password length and algorithm
        final int passwordBytesLength = password.getBytes(StandardCharsets.UTF_8).length;
        if (!allowWeakCrypto) {
            final int minimumSafePasswordLength = PasswordBasedEncryptor.getMinimumSafePasswordLength();
            if (passwordBytesLength < minimumSafePasswordLength) {
                validationResults.add(new ValidationResult.Builder().subject(PASSWORD_PD_NAME)
                        .explanation("Password length less than " + minimumSafePasswordLength + " characters is potentially unsafe. See Admin Guide.").build());
            }
        }

        // Multiple checks on machine with limited strength crypto
        if (limitedStrengthCrypto) {
            // Cannot use unlimited strength ciphers on machine that lacks policies
            if (encryptionMethod.isUnlimitedStrength()) {
                validationResults.add(new ValidationResult.Builder().subject(ENCRYPTION_ALGORITHM_PD_NAME)
                        .explanation(encryptionMethod.name() + " (" + encryptionMethod.getAlgorithm() + ") is not supported by this JVM due to lacking JCE Unlimited " +
                                "Strength Jurisdiction Policy files. See Admin Guide.").build());
            }

            // Check if the password exceeds the limit
            final boolean passwordLongerThanLimit = !CipherUtility.passwordLengthIsValidForAlgorithmOnLimitedStrengthCrypto(passwordBytesLength, encryptionMethod);
            if (passwordLongerThanLimit) {
                int maxPasswordLength = CipherUtility.getMaximumPasswordLengthForAlgorithmOnLimitedStrengthCrypto(encryptionMethod);
                validationResults.add(new ValidationResult.Builder().subject(PASSWORD_PD_NAME)
                        .explanation("Password length greater than " + maxPasswordLength + " characters is not supported by this JVM" +
                                " due to lacking JCE Unlimited Strength Jurisdiction Policy files. See Admin Guide.").build());
            }
        }

        // Check the KDF for compatibility with this algorithm
        List<String> kdfsForPBECipher = getKDFsForPBECipher(encryptionMethod);
        if (kdf == null || !kdfsForPBECipher.contains(kdf.name())) {
            // TODO: Get displayName
            final String displayName = KEY_DERIVATION_FUNCTION_PD_NAME;
            validationResults.add(new ValidationResult.Builder().subject(displayName)
                    .explanation(displayName + " is required to be " + StringUtils.join(kdfsForPBECipher,
                            ", ") + " when using algorithm " + encryptionMethod.getAlgorithm() + ". See Admin Guide.").build());
        }

        return validationResults;
    }

    public static List<ValidationResult> validateKeyed(EncryptionMethod encryptionMethod, KeyDerivationFunction kdf, String keyHex) {
        List<ValidationResult> validationResults = new ArrayList<>();
        boolean limitedStrengthCrypto = !PasswordBasedEncryptor.supportsUnlimitedStrength();

        if (limitedStrengthCrypto) {
            if (encryptionMethod.isUnlimitedStrength()) {
                validationResults.add(new ValidationResult.Builder().subject(ENCRYPTION_ALGORITHM_PD_NAME)
                        .explanation(encryptionMethod.name() + " (" + encryptionMethod.getAlgorithm() + ") is not supported by this JVM due to lacking JCE Unlimited " +
                                "Strength Jurisdiction Policy files. See Admin Guide.").build());
            }
        }
        int allowedKeyLength = PasswordBasedEncryptor.getMaxAllowedKeyLength(ENCRYPTION_ALGORITHM_PD_NAME);

        if (StringUtils.isEmpty(keyHex)) {
            validationResults.add(new ValidationResult.Builder().subject(RAW_KEY_HEX_PD_NAME)
                    .explanation(RAW_KEY_HEX_PD_NAME + " is required when using algorithm " + encryptionMethod.getAlgorithm() + ". See Admin Guide.").build());
        } else {
            byte[] keyBytes = new byte[0];
            try {
                keyBytes = Hex.decodeHex(keyHex.toCharArray());
            } catch (DecoderException e) {
                validationResults.add(new ValidationResult.Builder().subject(RAW_KEY_HEX_PD_NAME)
                        .explanation("Key must be valid hexadecimal string. See Admin Guide.").build());
            }
            if (keyBytes.length * 8 > allowedKeyLength) {
                validationResults.add(new ValidationResult.Builder().subject(RAW_KEY_HEX_PD_NAME)
                        .explanation("Key length greater than " + allowedKeyLength + " bits is not supported by this JVM" +
                                " due to lacking JCE Unlimited Strength Jurisdiction Policy files. See Admin Guide.").build());
            }
            if (!CipherUtility.isValidKeyLengthForAlgorithm(keyBytes.length * 8, encryptionMethod.getAlgorithm())) {
                List<Integer> validKeyLengths = CipherUtility.getValidKeyLengthsForAlgorithm(encryptionMethod.getAlgorithm());
                validationResults.add(new ValidationResult.Builder().subject(RAW_KEY_HEX_PD_NAME)
                        .explanation("Key must be valid length [" + StringUtils.join(validKeyLengths, ", ") + "]. See Admin Guide.").build());
            }
        }

        // Perform some analysis on the selected encryption algorithm to ensure the JVM can support it and the associated key

        List<String> kdfsForKeyedCipher = getKDFsForKeyedCipher();
        if (kdf == null || !kdfsForKeyedCipher.contains(kdf.name())) {
            validationResults.add(new ValidationResult.Builder().subject(KEY_DERIVATION_FUNCTION_PD_NAME)
                    .explanation(KEY_DERIVATION_FUNCTION_PD_NAME + " is required to be " + StringUtils.join(kdfsForKeyedCipher, ", ") + " when using algorithm " +
                            encryptionMethod.getAlgorithm()).build());
        }

        return validationResults;
    }

    static List<String> getKDFsForKeyedCipher() {
        List<String> kdfsForKeyedCipher = new ArrayList<>();
        kdfsForKeyedCipher.add(KeyDerivationFunction.NONE.name());
        for (KeyDerivationFunction k : KeyDerivationFunction.values()) {
            if (k.isStrongKDF()) {
                kdfsForKeyedCipher.add(k.name());
            }
        }
        return kdfsForKeyedCipher;
    }

    static List<String> getKDFsForPBECipher(EncryptionMethod encryptionMethod) {
        List<String> kdfsForPBECipher = new ArrayList<>();
        for (KeyDerivationFunction k : KeyDerivationFunction.values()) {
            // Add all weak (legacy) KDFs except NONE
            if (!k.isStrongKDF() && !k.equals(KeyDerivationFunction.NONE)) {
                kdfsForPBECipher.add(k.name());
                // If this algorithm supports strong KDFs, add them as well
            } else if ((encryptionMethod.isCompatibleWithStrongKDFs() && k.isStrongKDF())) {
                kdfsForPBECipher.add(k.name());
            }
        }
        return kdfsForPBECipher;
    }

    public static Collection<ValidationResult> standardValidate(final ValidationContext context, List<ValidationResult> validationResults) {
        final String methodValue = context.getProperty(ENCRYPTION_ALGORITHM).getValue();
        final EncryptionMethod encryptionMethod = EncryptionMethod.valueOf(methodValue);
        final String algorithm = encryptionMethod.getAlgorithm();
        final String password = context.getProperty(PASSWORD).getValue();
        final KeyDerivationFunction kdf = KeyDerivationFunction.valueOf(context.getProperty(KEY_DERIVATION_FUNCTION).getValue());
        final String keyHex = context.getProperty(RAW_KEY_HEX).getValue();
        if (EncryptProcessorUtils.isPGPAlgorithm(algorithm)) {
            final boolean encrypt = context.getProperty(MODE).getValue().equalsIgnoreCase(ENCRYPT_MODE);
            final String publicKeyring = context.getProperty(PUBLIC_KEYRING).getValue();
            final String publicUserId = context.getProperty(PUBLIC_KEY_USERID).getValue();
            final String privateKeyring = context.getProperty(PRIVATE_KEYRING).getValue();
            final String privateKeyringPassphrase = context.getProperty(PRIVATE_KEYRING_PASSPHRASE).getValue();
            validationResults.addAll(EncryptProcessorUtils.validatePGP(encryptionMethod, password, encrypt, publicKeyring, publicUserId, privateKeyring, privateKeyringPassphrase));
        } else { // Not PGP
            if (encryptionMethod.isKeyedCipher()) { // Raw key
                validationResults.addAll(EncryptProcessorUtils.validateKeyed(encryptionMethod, kdf, keyHex));
            } else { // PBE
                boolean allowWeakCrypto = context.getProperty(ALLOW_WEAK_CRYPTO).getValue().equalsIgnoreCase(WEAK_CRYPTO_ALLOWED_NAME);
                validationResults.addAll(EncryptProcessorUtils.validatePBE(encryptionMethod, kdf, password, allowWeakCrypto));
            }
        }
        return validationResults;
    }

    public interface Encryptor {
        StreamCallback getEncryptionCallback() throws Exception;

        StreamCallback getDecryptionCallback() throws Exception;
    }
}
