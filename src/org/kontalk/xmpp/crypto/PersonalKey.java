/*
 * Kontalk Android client
 * Copyright (C) 2013 Kontalk Devteam <devteam@kontalk.org>

 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.kontalk.xmpp.crypto;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.NoSuchProviderException;
import java.security.SignatureException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Iterator;

import org.kontalk.xmpp.Kontalk;
import org.kontalk.xmpp.crypto.PGP.PGPDecryptedKeyPairRing;
import org.kontalk.xmpp.crypto.PGP.PGPKeyPairRing;
import org.spongycastle.openpgp.PGPException;
import org.spongycastle.openpgp.PGPKeyPair;
import org.spongycastle.openpgp.PGPObjectFactory;
import org.spongycastle.openpgp.PGPPrivateKey;
import org.spongycastle.openpgp.PGPPublicKey;
import org.spongycastle.openpgp.PGPPublicKeyRing;
import org.spongycastle.openpgp.PGPSecretKey;
import org.spongycastle.openpgp.PGPSecretKeyRing;
import org.spongycastle.openpgp.operator.KeyFingerPrintCalculator;
import org.spongycastle.openpgp.operator.PBESecretKeyDecryptor;
import org.spongycastle.openpgp.operator.PGPDigestCalculatorProvider;
import org.spongycastle.openpgp.operator.bc.BcKeyFingerprintCalculator;
import org.spongycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder;
import org.spongycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder;

import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;


/** Personal asymmetric encryption key. */
public class PersonalKey implements Parcelable {

    /** Decrypted key pair (for direct usage). */
    private final PGPDecryptedKeyPairRing mPair;
    /** X.509 bridge certificate. */
    private X509Certificate mBridgeCert;

    private PersonalKey(PGPDecryptedKeyPairRing keyPair, X509Certificate bridgeCert) {
        mPair = keyPair;
        mBridgeCert = bridgeCert;
    }

    private PersonalKey(PGPKeyPair signKp, PGPKeyPair encryptKp, X509Certificate bridgeCert) {
        this(new PGPDecryptedKeyPairRing(signKp, encryptKp), bridgeCert);
    }

    private PersonalKey(Parcel in) throws PGPException {
        mPair = PGP.fromParcel(in);
        mBridgeCert = X509Bridge.fromParcel(in);
    }

    public PGPKeyPair getEncryptKeyPair() {
        return mPair.encryptKey;
    }

    public PGPKeyPair getSignKeyPair() {
        return mPair.signKey;
    }

    public X509Certificate getBridgeCertificate() {
        return mBridgeCert;
    }

    /** Returns the first user ID on the key that matches the given network. */
    public String getUserId(String network) {
        return PGP.getUserId(mPair.signKey.getPublicKey(), network);
    }

    public PGPKeyPairRing store(String userId, String network, String passphrase) throws PGPException {
        return store("TEST",
            userId + '@' + network, "NO COMMENT",
            passphrase);
    }

    public PGPKeyPairRing store(String name, String email, String comment, String passphrase) throws PGPException {
        // name[ (comment)] <[email]>
        StringBuilder userid = new StringBuilder(name);

        if (comment != null) userid
            .append(" (")
            .append(comment)
            .append(')');

        userid.append(" <");
        if (email != null)
            userid.append(email);
        userid.append('>');

        return PGP.store(mPair, userid.toString(), passphrase);
    }

    /**
     * Updates the public key.
     * @return the public keyring.
     */
    public PGPPublicKeyRing update(byte[] keyData) throws IOException {
        PGPPublicKeyRing ring = new PGPPublicKeyRing(keyData, new BcKeyFingerprintCalculator());
        mPair.signKey = new PGPKeyPair(ring.getPublicKey(), mPair.signKey.getPrivateKey());
        return ring;
    }

    /** Creates a {@link PersonalKey} from private and public key byte buffers. */
    @SuppressWarnings("unchecked")
    public static PersonalKey load(byte[] privateKeyData, byte[] publicKeyData, String passphrase, byte[] bridgeCertData)
            throws PGPException, IOException, CertificateException, NoSuchProviderException {

        KeyFingerPrintCalculator fpr = new BcKeyFingerprintCalculator();
        PGPSecretKeyRing secRing = new PGPSecretKeyRing(privateKeyData, fpr);
        PGPPublicKeyRing pubRing = new PGPPublicKeyRing(publicKeyData, fpr);

        PGPDigestCalculatorProvider sha1Calc = new JcaPGPDigestCalculatorProviderBuilder().build();
        PBESecretKeyDecryptor decryptor = new JcePBESecretKeyDecryptorBuilder(sha1Calc)
            .setProvider(PGP.PROVIDER)
            .build(passphrase.toCharArray());

        PGPKeyPair signKp, encryptKp;

        PGPPublicKey  signPub = null;
        PGPPrivateKey signPriv = null;
        PGPPublicKey   encPub = null;
        PGPPrivateKey  encPriv = null;

        // public keys
        Iterator<PGPPublicKey> pkeys = pubRing.getPublicKeys();
        while (pkeys.hasNext()) {
            PGPPublicKey key = pkeys.next();
            if (key.isMasterKey()) {
                // master (signing) key
                signPub = key;
            }
            else {
                // sub (encryption) key
                encPub = key;
            }
        }

        // secret keys
        Iterator<PGPSecretKey> skeys = secRing.getSecretKeys();
        while (skeys.hasNext()) {
            PGPSecretKey key = skeys.next();
            PGPSecretKey sec = secRing.getSecretKey();
            if (key.isMasterKey()) {
                // master (signing) key
                signPriv = sec.extractPrivateKey(decryptor);
            }
            else {
                // sub (encryption) key
                encPriv = sec.extractPrivateKey(decryptor);
            }
        }

        // X.509 bridge certificate
        CertificateFactory certFactory = CertificateFactory.getInstance("X.509", PGP.PROVIDER);
        InputStream in = new ByteArrayInputStream(bridgeCertData);
        X509Certificate bridgeCert = (X509Certificate) certFactory.generateCertificate(in);

        /* TEST
        X500Principal subject = bridgeCert.getSubjectX500Principal();
        Log.d(Kontalk.TAG, "subject <" + subject.toString() + "> (" + subject.getName() + ")");

        FileOutputStream fout = new FileOutputStream("/sdcard/bridge.crt");
        fout.write(bridgeCertData);
        fout.close();

        fout = new FileOutputStream("/sdcard/private.key");
        fout.write(privateKeyData);
        fout.close();

        fout = new FileOutputStream("/sdcard/public.key");
        fout.write(publicKeyData);
        fout.close();
         */

        if (encPriv != null && encPub != null && signPriv != null && signPub != null && bridgeCert != null) {
            signKp = new PGPKeyPair(signPub, signPriv);
            encryptKp = new PGPKeyPair(encPub, encPriv);
            return new PersonalKey(signKp, encryptKp, bridgeCert);
        }

        throw new PGPException("invalid key data");
    }

    public static PersonalKey create() throws IOException {
        try {
            PGPDecryptedKeyPairRing kp = PGP.create();
            return new PersonalKey(kp, null);
        }
        catch (Exception e) {
            IOException io = new IOException("unable to generate keypair");
            io.initCause(e);
            throw io;
        }
    }

    @SuppressWarnings("unchecked")
    public PGPPublicKeyRing signPublicKey(byte[] publicKeyring, String id)
            throws PGPException, IOException, SignatureException {

        PGPObjectFactory reader = new PGPObjectFactory(publicKeyring);
        Object o = reader.nextObject();
        while (o != null) {
            Log.v("PersonalKey", o.toString());
            if (o instanceof PGPPublicKeyRing) {
                PGPPublicKeyRing pubRing = (PGPPublicKeyRing) o;
                Iterator<PGPPublicKey> iter = pubRing.getPublicKeys();
                while (iter.hasNext()) {
                    PGPPublicKey pk = iter.next();
                    if (pk.isMasterKey()) {
                        PGPPublicKey signed = signPublicKey(pk, id);
                        return PGPPublicKeyRing.insertPublicKey(pubRing, signed);
                    }
                }
            }
            o = reader.nextObject();
        }

        throw new PGPException("invalid keyring data.");
    }

    public PGPPublicKey signPublicKey(PGPPublicKey keyToBeSigned, String id)
            throws PGPException, IOException, SignatureException {

        return PGP.signPublicKey(mPair.signKey, keyToBeSigned, id);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        // TODO write byte arrays
        try {
            PGP.toParcel(mPair, dest);
        }
        catch (Exception e) {
            throw new RuntimeException("error writing key to parcel", e);
        }
    }

    public static final Parcelable.Creator<PersonalKey> CREATOR =
            new Parcelable.Creator<PersonalKey>() {
        public PersonalKey createFromParcel(Parcel source) {
            try {
                return new PersonalKey(source);
            }
            catch (PGPException e) {
                Log.w(Kontalk.TAG, "error creating from parcel", e);
                return null;
            }
        }

        @Override
        public PersonalKey[] newArray(int size) {
            return new PersonalKey[size];
        };
    };

}