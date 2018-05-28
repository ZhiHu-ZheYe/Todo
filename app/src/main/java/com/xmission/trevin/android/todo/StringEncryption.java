/*
 * $Id: StringEncryption.java,v 1.3 2014/03/22 20:08:08 trevin Exp trevin $
 * Copyright © 2011 Trevin Beattie
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * $Log: StringEncryption.java,v $
 * Revision 1.3  2014/03/22 20:08:08  trevin
 * Added the copyright notice.
 * Created a local cryptography library (as a subset of BouncyCastle)
 *   to replace Android’s built-in cryptography, which does not
 *   generate encryption keys consistently across Android versions.
 * Added a no-parameter releaseGlobalEncryption() for use by Services.
 * Added local parameters for the salt length, key length, and key
 *   iteration count.  Store these along with the hashed password key
 *   so that these can be changed without invalidating previously
 *   stored password hashes.
 * Make METADATA_PASSWORD_HASH available to XMLImporterService.
 * Before forgetting a password and key, fill them with 0’s for security.
 * Changed the password data type from a String to char[] array
 *   to prevent Java from caching the password.
 * Use SecureRandom instead of plain Random to generate salt.
 * Changed the stored password hash from a simple SHA digest of the salt
 *   + password to a SHA256 digest of the salt + encryption key.
 * Added encrypt(byte[]) and decryptBytes(byte[]).
 *
 * Revision 1.2  2011/05/10 03:44:32  trevin
 * Allow any context wrapper to be passed.
 * Use a regular query rather than a managed query.
 * Don't automatically set a new password in checkPassword;
 *   use a separate method for that.
 * Added a method to remove an existing password.
 *
 * Revision 1.1  2011/01/19 00:19:28  trevin
 * Initial revision
 *
 */
package com.xmission.trevin.android.todo;

import java.io.UnsupportedEncodingException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.*;
import java.util.Arrays;

import javax.crypto.*;
import javax.crypto.spec.SecretKeySpec;

import com.xmission.trevin.android.crypto.*;
import com.xmission.trevin.android.todo.ToDo.ToDoMetadata;

import android.content.*;
import android.database.Cursor;
import android.util.Log;

/**
 * Utilities for encrypting and decrypting private strings.
 *
 * @author Trevin Beattie
 */
public class StringEncryption {

    public static final String LOG_TAG = "StringEncryption";

    /** Global encryption object */
    private static StringEncryption globalEncryption = null;

    /** The number of active references using the global encryption object */
    private static int globalReferences = 0;

    private static final SecureRandom RAND = new SecureRandom();

    /**
     * Let the encryption class know that an activity or service is using
     * encryption.  All activities requesting encryption will use the same
     * object, which is expected to have the password set by the preferences
     * activity.
     */
    public static StringEncryption holdGlobalEncryption() {
	Log.d(LOG_TAG, ".holdGlobalEncryption(" + globalReferences + ","
		+ globalEncryption + ")");
	if (globalEncryption == null) {
	    globalEncryption = new StringEncryption();
	    globalReferences = 0;
	}
	globalReferences++;
	return globalEncryption;
    }

    /**
     * Let the encryption class know that an activity is finished using
     * encryption.  When the last activity using encryption has finished,
     * the password and key will be forgotten.  This will be reflected
     * in the "show encrypted" (hidden) preferences item.
     * <p>
     * <b>Do not</b> call this from a service; it will cause a
     * CalledFromWrongThreadException!
     */
    public static void releaseGlobalEncryption(ContextWrapper context) {
	Log.d(LOG_TAG, ".releaseGlobalEncryption(" + globalReferences + ","
		+ globalEncryption + ")");
	if (--globalReferences <= 0) {
	    if (globalReferences < 0)
		Log.e(LOG_TAG, "A caller (maybe " + context
			+ ") released encryption without holding it!");
	    if (globalEncryption != null) {
		globalEncryption.forgetPassword();
		context.getSharedPreferences(ToDoListActivity.TODO_PREFERENCES,
			Context.MODE_PRIVATE).edit().putBoolean(
				ToDoListActivity.TPREF_SHOW_ENCRYPTED,
				false).commit();
	    }
	    globalEncryption = null;
	}
    }

    /**
     * Let the encryption class know that a service is finished using
     * encryption.
     */
    public static void releaseGlobalEncryption() {
	Log.d(LOG_TAG, ".releaseGlobalEncryption(" + globalReferences + ","
		+ globalEncryption + ")");
	if (globalReferences <= 0)
	    Log.e(LOG_TAG, "An unknown caller released encryption without holding it!");
	else
	    --globalReferences;
    }

    /**
     * The password used to generate the key.
     * This must be a char array so that it will not be cached!
     */
    private char[] userPassword = null;

    /** Salt for the password */
    private byte[] salt = null;
    /** Default number of bytes of salt; should equal the key length in bytes */
    private static final int SALT_LENGTH = 32;
    /** Key length in bits */
    private static final int KEY_LENGTH = 256;
    /** Default number of times to iterate the salted password to get the key */
    private static final int KEY_ITERATION_COUNT = 1000;

    /** Key length in bits in this instance */
    private int keyLength = KEY_LENGTH;

    /**
     * Number of times to iterate the salted password to get the key
     * in this instance
     */
    private int keyIterationCount = KEY_ITERATION_COUNT;

    /** The encryption key */
    private byte[] key = null;

    /** Metadata projection fields */
    private final static String[] METADATA_PROJECTION = { ToDoMetadata.VALUE };

    /** Name of the metadata used to store the hash of the user's password */
    final static String[] METADATA_PASSWORD_HASH = {
	    "StringEncryption.HashedPassword" };

    private final static String[] COUNT_PROJECTION = { ToDo.ToDoItem._ID };

    /** @return whether the encryption key has been set */
    public boolean hasKey() { return key != null; }

    /**
     * Clear the password and key.  This should be called
     * when the user chooses to hide private records.
     */
    public void forgetPassword() {
	if (key != null) {
	    Arrays.fill(key, (byte) 0);
	    key = null;
	}
	salt = null;
	if (userPassword != null) {
	    Arrays.fill(userPassword, (char) 0);
	    userPassword = null;
	}
	userPassword = null;
    }

    /**
     * Get the password which was set on this encryption object.
     * @return the password (in clear text), or <code>null</code>
     * if no password is set.
     */
    public char[] getPassword() {
	if (userPassword == null)
	    return null;
	char[] copy = new char[userPassword.length];
	System.arraycopy(userPassword, 0, copy, 0, userPassword.length);
	return copy;
    }

    /**
     * Set the password.  The password will be used to generate
     * the private key.  This password is <i>not</i> checked against
     * what was previously recorded in the database; this makes it
     * possible to set up two different encryption objects in case
     * the user is changing his/her password.
     *
     * @return true if the password is has successfully
     * been set up, false if the password cannot be used.
     */
    public void setPassword(char[] password) {
	userPassword = new char[password.length];
	System.arraycopy(password, 0, userPassword, 0, password.length);
	if (key != null) {
	    Arrays.fill(key, (byte) 0);
	    key = null;
	}
    }

    /**
     * Add some salt
     */
    public void addSalt() {
	salt = new byte[SALT_LENGTH];
	RAND.nextBytes(salt);
	if (key != null) {
	    Arrays.fill(key, (byte) 0);
	    key = null;
	}
    }

    /**
     * @return whether a password has been set on the database.
     */
    public boolean hasPassword(ContentResolver resolver) {
	Cursor c = resolver.query(
		ToDoMetadata.CONTENT_URI, METADATA_PROJECTION,
		ToDoMetadata.NAME + " = ?", METADATA_PASSWORD_HASH, null);
	try {
	    return c.moveToFirst();
	} finally {
	    c.close();
	}
    }

    /**
     * Check the password against what was stored in the database.
     *
     * @return true if the password matches, false if it does not match.
     */
    public boolean checkPassword(ContentResolver resolver)
		throws GeneralSecurityException {
	byte[] hashedPassword = null;
	Cursor c = resolver.query(
		ToDoMetadata.CONTENT_URI, METADATA_PROJECTION,
		ToDoMetadata.NAME + " = ?", METADATA_PASSWORD_HASH, null);
	try {
	    if (c.moveToFirst()) {
		hashedPassword = c.getBlob(c.getColumnIndex(ToDoMetadata.VALUE));
	    } else {
		throw new IllegalStateException(
			"checkPassword(resolver) called with no password in the database");
	    }
	}
	finally {
	    c.close();
	}
	return checkPassword(hashedPassword);
    }

    /**
     * Check the password against a key hash, using salt from the hash.
     *
     * @return true if the password matches, false if it does not match.
     */
    public boolean checkPassword(byte[] hashedPassword)
		throws GeneralSecurityException {
	if (hashedPassword == null)
	    return false;
	ByteBuffer bb = ByteBuffer.wrap(hashedPassword).order(ByteOrder.BIG_ENDIAN);
	byte[] storedHash;
	int hLen = 0;
	try {
	    if (bb.get() != 2)
		throw new UnrecoverableKeyException("Unsupported encryption method");
	    salt = new byte[(bb.get() & 0xff) + 2];
	    keyLength = ((bb.getShort() & 0xffff) + 2) * 8;
	    keyIterationCount = (bb.getShort() & 0xffff) + 1;
	    bb.get(salt);
	    hLen = bb.position();
	    storedHash = new byte[bb.limit() - bb.position()];
	    bb.get(storedHash);
	} catch (BufferUnderflowException bux) {
	    throw new UnrecoverableKeyException("Invalid password hash");
	}

	// Tentatively generate a key from the assumed password
	generateKey();

	// Hash it and see if it matches the stored hash
	MessageDigest md = new SHA256.Digest();
	md.update(hashedPassword, 0, hLen);
	md.update(key);
	byte[] hash = md.digest();
	if (Arrays.equals(storedHash, hash))
	    return true;

	// If it does not match, discard the key and salt we got from the input.
	Arrays.fill(key, (byte) 0);
	key = null;
	Arrays.fill(salt, (byte) 0);
	salt = null;
	return false;
    }

    /**
     * Store the salt and hashed password key in the database.
     * The stored bytes consists of:
     * <table>
     *   <tr><th>Size</th><th>Content</th></tr>
     *   <tr><td>1</td><td>Encryption scheme <i>(only "2" in this version,
     *   representing a PKCS5S2 key hashed by SHA256, and AES cipher.)</i></td></tr>
     *   <tr><td>1</td><td>Number of bytes of salt (unsigned, bias 2)</td></tr>
     *   <tr><td>2</td><td>Number of bytes in the encryption key
     *   (unsigned, bias 2, in MSB order)</td></tr>
     *   <tr><td>2</td><td>Iteration count for key derivation
     *   (unsigned, bias 1, in MSB order)</td></tr>
     *   <tr><td>?</td><td>Salt bytes</td></tr>
     *   <tr><td>?</td><td>The result of hashing the above header, salt,
     *   and encryption key with SHA256.</td></tr>
     * </table>
     * <p>
     * If any records in the database have been encrypted,
     * they must be decrypted with the old password and
     * the old password removed before committing the
     * new password to the database.  All changes must be
     * done as a transaction with the database locked!
     */
    public void storePassword(ContentResolver resolver)
		throws GeneralSecurityException {
	if (key == null) {
	    if (salt == null)
		addSalt();
	    generateKey();
	}

	byte[] header = new byte[6];
	ByteBuffer bb = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
	bb.put((byte) 2);
	bb.put((byte) (salt.length - 2));
	bb.putShort((short) (keyLength / 8 - 2));
	bb.putShort((short) (keyIterationCount - 1));
	MessageDigest md = new SHA256.Digest();
	md.update(header);
	md.update(salt);
	md.update(key);
	byte[] hash = md.digest();

	// Combine the header, salt, and hash
	byte[] hash2 = new byte[header.length + salt.length + hash.length];
	System.arraycopy(header, 0, hash2, 0, header.length);
	System.arraycopy(salt, 0, hash2, header.length, salt.length);
	System.arraycopy(hash, 0, hash2, header.length + salt.length, hash.length);

	ContentValues values = new ContentValues();
	values.put(ToDoMetadata.NAME, METADATA_PASSWORD_HASH[0]);
	values.put(ToDoMetadata.VALUE, hash2);
	resolver.insert(ToDoMetadata.CONTENT_URI, values);
    }

    /**
     * Remove the stored password from the database.
     * <p>
     * Any encrypted records in the database must be successfully decrypted
     * before the old password is removed!
     */
    public void removePassword(ContentResolver resolver) {
	Cursor c = resolver.query(ToDo.ToDoItem.CONTENT_URI, COUNT_PROJECTION,
		ToDo.ToDoItem.PRIVATE + " > 1", null, null);
	try {
	    if (c.moveToFirst())
		// There are encrypted records!
		throw new IllegalStateException(c.getInt(c.getColumnIndex(
			ToDo.ToDoItem._COUNT)) + " records are still encrypted");
	} finally {
	    c.close();
	}
	resolver.delete(ToDoMetadata.CONTENT_URI,
		ToDoMetadata.NAME + " = ?", METADATA_PASSWORD_HASH);
    }

    /**
     * Generate the key from the password and salt.
     *
     * @throw IllegalStateException if the password and salt have not been set.
     */
    private void generateKey()
		throws GeneralSecurityException, IllegalStateException {
	if (userPassword == null)
	    throw new IllegalStateException("Password is not set");
	if (salt == null)
	    throw new IllegalStateException("No salt");
	/*
	 * Android 4.4 changed the behavior of PBKDF2WithHmacSHA1
	 * to use the UTF-8 encoding of the password instead of
	 * the lower 8 bytes of each character.  Android 2.2 does
	 * not support this algorithm.  To avoid decryption
	 * errors when switching between platforms, do our own
	 * encoding and call a local copy of the algorithm.
	 */
	byte[] passwordKey =
	    PKCS5S2ParametersGenerator.PKCS5PasswordToUTF8Bytes(userPassword);
	PKCS5S2ParametersGenerator generator = new PKCS5S2ParametersGenerator();
	generator.init(passwordKey, salt, keyIterationCount);
	KeyParameter param = generator.generateDerivedMacParameters(keyLength);
	Arrays.fill(passwordKey, (byte) 0);
	key = param.getKey();
    }

    /**
     * Encrypt a byte array.
     *
     * @param orig the unencrypted bytes
     *
     * @return the encrypted bytes
     *
     * @throws IllegalStateException if the password has not been provided
     */
    public byte[] encrypt(byte[] orig)
		throws GeneralSecurityException, IllegalStateException {
	if (orig == null)
	    return null;
	if (key == null)
	    generateKey();
	try {
	    SecretKeySpec spec = new SecretKeySpec(key, "AES");
	    AESCipher cipher = new AESCipher();
	    cipher.init(Cipher.ENCRYPT_MODE, spec);
	    return cipher.doFinal(orig);
	} catch (GeneralSecurityException gsx) {
	    forgetPassword();
	    throw gsx;
	}
    }

    /**
     * Encrypt a string.
     *
     * @param orig the unencrypted string
     *
     * @return the encrypted bytes
     *
     * @throws IllegalStateException if the password has not been provided
     */
    public byte[] encrypt(String orig)
		throws GeneralSecurityException, IllegalStateException {
	if (orig == null)
	    return null;
	try {
	    return encrypt(orig.getBytes("UTF-8"));
	} catch (UnsupportedEncodingException uex) {
	    throw new IllegalStateException("UTF-8 is not supported!", uex);
	}
    }

    /**
     * Decrypt a byte array.
     *
     * @param code the encrypted bytes
     *
     * @return the decrypted string
     *
     * @throws IllegalStateException if the password has not been provided
     * @throws InvalidKeyException if the decryption does not result
     * in a valid string.
     */
    public byte[] decryptBytes(byte[] code)
		throws GeneralSecurityException, IllegalStateException {
	if (code == null)
	    return null;
	if (key == null)
	    generateKey();
	try {
	    SecretKeySpec spec = new SecretKeySpec(key, "AES");
	    AESCipher cipher = new AESCipher();
	    cipher.init(Cipher.DECRYPT_MODE, spec);
	    return cipher.doFinal(code);
	} catch (IllegalBlockSizeException ibsx) {
	    throw new InvalidKeyException(
		    "Could not decode data using the given password", ibsx);
	} catch (BadPaddingException bpx) {
	    throw new InvalidKeyException(
		    "Could not decode data using the given password", bpx);
	}
    }

    /**
     * Decrypt a string.
     *
     * @param code the encrypted bytes
     *
     * @return the decrypted string
     *
     * @throws IllegalStateException if the password has not been provided
     * @throws InvalidKeyException if the decryption does not result
     * in a valid string.
     */
    public String decrypt(byte[] code)
		throws GeneralSecurityException, IllegalStateException {
	if (code == null)
	    return null;
	try {
	    byte[] candidate = decryptBytes(code);
	    return new String(candidate, "UTF-8");
	} catch (UnsupportedEncodingException uex) {
	    throw new InvalidKeyException(
		    "Could not decode data using the given password", uex);
	}
    }
}
