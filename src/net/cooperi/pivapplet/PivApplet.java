/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2017, Alex Wilson <alex@cooperi.net>
 */

package net.cooperi.pivapplet;

import javacard.framework.APDU;
import javacard.framework.Applet;
import javacard.framework.ISO7816;
import javacard.framework.ISOException;
import javacard.framework.JCSystem;
import javacard.framework.OwnerPIN;
import javacard.framework.Util;
import javacard.framework.APDUException;
import javacard.security.CryptoException;
import javacard.security.ECKey;
import javacard.security.ECPrivateKey;
import javacard.security.ECPublicKey;
import javacard.security.DESKey;
import javacard.security.Key;
import javacard.security.KeyBuilder;
import javacard.security.KeyPair;
import javacard.security.RandomData;
import javacard.security.RSAPrivateCrtKey;
import javacard.security.RSAPublicKey;
import javacard.security.Signature;
import javacard.security.SecretKey;
import javacardx.crypto.Cipher;
import javacardx.apdu.ExtendedLength;

public class PivApplet extends Applet implements ExtendedLength
{
	private static final byte[] PIV_AID = {
	    (byte)0xa0, (byte)0x00, (byte)0x00, (byte)0x03, (byte)0x08,
	    (byte)0x00, (byte)0x00, (byte)0x10, (byte)0x00, (byte)0x01,
	    (byte)0x00
	};

	private static final byte[] APP_NAME = {
	    'P', 'i', 'v', 'A', 'p', 'p', 'l', 'e', 't'
	};

	private static final byte[] DEFAULT_ADMIN_KEY = {
	    (byte)0x01, (byte)0x02, (byte)0x03, (byte)0x04,
	    (byte)0x05, (byte)0x06, (byte)0x07, (byte)0x08,
	    (byte)0x01, (byte)0x02, (byte)0x03, (byte)0x04,
	    (byte)0x05, (byte)0x06, (byte)0x07, (byte)0x08,
	    (byte)0x01, (byte)0x02, (byte)0x03, (byte)0x04,
	    (byte)0x05, (byte)0x06, (byte)0x07, (byte)0x08
	};

	private static final byte[] DEFAULT_PIN = {
	    '1', '2', '3', '4', '5', '6', (byte)0xFF, (byte)0xFF
	};
	private static final byte[] DEFAULT_PUK = {
	    '1', '2', '3', '4', '5', '6', '7', '8'
	};

	private static final byte INS_VERIFY = (byte)0x20;
	private static final byte INS_CHANGE_PIN = (byte)0x24;
	private static final byte INS_RESET_PIN = (byte)0x2C;
	private static final byte INS_GEN_AUTH = (byte)0x87;
	private static final byte INS_GET_DATA = (byte)0xCB;
	private static final byte INS_PUT_DATA = (byte)0xDB;
	private static final byte INS_GEN_ASYM = (byte)0x47;
	private static final byte INS_GET_RESPONSE = (byte)0xC0;

	private static final byte INS_SET_MGMT = (byte)0xff;
	private static final byte INS_IMPORT_ASYM = (byte)0xfe;
	private static final byte INS_GET_VER = (byte)0xfd;

	private static final short RAM_BUF_SIZE = (short)1024;
	private static final short MAX_CERT_SIZE = (short)900;

	private static final boolean USE_EXT_LEN = false;
	private static final byte RBSTAT_SEND_LEN = (byte)0;
	private static final byte RBSTAT_SEND_OFF = (byte)1;
	private static final byte RBSTAT_RECV_LEN = (byte)2;
	private static final byte RBSTAT_RECV_OFF = (byte)3;

	private byte[] ramBuf = null;
	private short[] rbStat = null;
	private byte[] challenge = null;
	private byte[] iv = null;

	private byte[] guid = null;
	private byte[] fascn = null;
	private byte[] expiry = null;
	private TlvStream tlv = null;

	private OwnerPIN pivPin = null;
	private OwnerPIN pukPin = null;

	private RandomData randData = null;
	private Cipher tripleDes = null;
	private Cipher aes128 = null;
	private Cipher rsaPkcs1 = null;

	private PivSlot slot9a = null, slot9b = null, slot9c = null,
	    slot9d = null, slot9e = null;

	private static final byte PIV_ALG_DEFAULT = (byte)0x00;
	private static final byte PIV_ALG_3DES = (byte)0x03;
	private static final byte PIV_ALG_RSA1024 = (byte)0x06;
	private static final byte PIV_ALG_RSA2048 = (byte)0x07;
	private static final byte PIV_ALG_AES128 = (byte)0x08;
	private static final byte PIV_ALG_AES192 = (byte)0x0A;
	private static final byte PIV_ALG_AES256 = (byte)0x0C;
	private static final byte PIV_ALG_ECCP256 = (byte)0x11;
	private static final byte PIV_ALG_ECCP384 = (byte)0x14;

	private static final byte GA_TAG_WITNESS = (byte)0x80;
	private static final byte GA_TAG_CHALLENGE = (byte)0x81;
	private static final byte GA_TAG_RESPONSE = (byte)0x82;
	private static final byte GA_TAG_EXP = (byte)0x85;

	private static final byte TAG_CARDCAP = (byte)0x07;
	private static final byte TAG_CHUID = (byte)0x02;
	private static final byte TAG_SECOBJ = (byte)0x06;

	private static final byte TAG_FINGERPRINTS = (byte)0x03;
	private static final byte TAG_FACE = (byte)0x08;

	private static final byte TAG_CERT_9A = (byte)0x05;
	private static final byte TAG_CERT_9C = (byte)0x0A;
	private static final byte TAG_CERT_9D = (byte)0x0B;
	private static final byte TAG_CERT_9E = (byte)0x01;

	public static void
	install(byte[] info, short off, byte len)
	{
		final PivApplet applet = new PivApplet();
		applet.register();
	}

	protected
	PivApplet()
	{
		randData = RandomData.getInstance(RandomData.ALG_SECURE_RANDOM);
		tripleDes = Cipher.getInstance(Cipher.ALG_DES_CBC_NOPAD, false);
		/*aes128 = Cipher.getInstance(Cipher.ALG_AES_BLOCK_128_ECB_NOPAD,
		    false);*/
		rsaPkcs1 = Cipher.getInstance(Cipher.ALG_RSA_NOPAD, false);

		ramBuf = JCSystem.makeTransientByteArray(RAM_BUF_SIZE,
		    JCSystem.CLEAR_ON_DESELECT);
		rbStat = JCSystem.makeTransientShortArray((short)4,
		    JCSystem.CLEAR_ON_DESELECT);
		challenge = JCSystem.makeTransientByteArray((short)16,
		    JCSystem.CLEAR_ON_DESELECT);
		iv = JCSystem.makeTransientByteArray((short)16,
		    JCSystem.CLEAR_ON_DESELECT);

		guid = new byte[16];
		randData.generateData(guid, (short)0, (short)16);
		fascn = new byte[25];
		expiry = new byte[] { '2', '0', '5', '0', '0', '1', '0', '1' };

		slot9a = new PivSlot();
		slot9b = new PivSlot();
		slot9c = new PivSlot();
		slot9d = new PivSlot();
		slot9e = new PivSlot();

		tlv = new TlvStream();

		/* Initialize the admin key */
		DESKey dk = (DESKey)KeyBuilder.buildKey(KeyBuilder.TYPE_DES,
		    KeyBuilder.LENGTH_DES3_3KEY, false);
		slot9b.sym = dk;
		dk.setKey(DEFAULT_ADMIN_KEY, (short)0);
		slot9b.symAlg = PIV_ALG_3DES;

		pivPin = new OwnerPIN((byte)5, (byte)8);
		pivPin.update(DEFAULT_PIN, (short)0, (byte)8);
		pukPin = new OwnerPIN((byte)3, (byte)8);
		pukPin.update(DEFAULT_PUK, (short)0, (byte)8);

		slot9a.needsPin = true;
		slot9a.pin = pivPin;

		slot9c.needsPin = true;
		slot9c.pin = pivPin;

		slot9d.needsPin = true;
		slot9d.pin = pivPin;
	}

	public void
	process(APDU apdu)
	{
		byte[] buffer = apdu.getBuffer();
		byte ins = buffer[ISO7816.OFFSET_INS];

		if (!apdu.isISOInterindustryCLA()) {
			ISOException.throwIt(ISO7816.SW_CLA_NOT_SUPPORTED);
			return;
		}

		if (selectingApplet()) {
			short len, le;
			le = apdu.setOutgoing();

			tlv.setTarget(null);

			tlv.push((byte)0x61);

			tlv.push((byte)0x4F);
			tlv.write(PIV_AID, (short)0, (short)PIV_AID.length);
			tlv.pop();

			tlv.push((byte)0x79);
			tlv.push((byte)0x4F);
			tlv.write(PIV_AID, (short)0, (short)PIV_AID.length);
			tlv.pop();
			tlv.pop();

			tlv.push((byte)0x50);
			tlv.write(APP_NAME, (short)0, (short)APP_NAME.length);
			tlv.pop();

			tlv.push((byte)0xAC);
			tlv.push((byte)0x80);
			tlv.writeByte(PIV_ALG_3DES);
			tlv.pop();
			tlv.push((byte)0x80);
			tlv.writeByte(PIV_ALG_RSA1024);
			tlv.pop();
			tlv.push((byte)0x80);
			tlv.writeByte(PIV_ALG_RSA2048);
			tlv.pop();
			tlv.pop();

			len = tlv.pop();

			len = le > len ? len : le;
			apdu.setOutgoingLength(len);
			apdu.sendBytes((short)0, len);
			return;
		}

		switch (ins) {
		case INS_GET_DATA:
			processGetData(apdu);
			break;
		case INS_GEN_AUTH:
			processGeneralAuth(apdu);
			break;
		case INS_PUT_DATA:
			processPutData(apdu);
			break;
		case INS_CHANGE_PIN:
			processChangePin(apdu);
			break;
		case INS_VERIFY:
			processVerify(apdu);
			break;
		case INS_GEN_ASYM:
			processGenAsym(apdu);
			break;
		case INS_GET_RESPONSE:
			continueResponse(apdu);
			break;
		default:
			ISOException.throwIt(ISO7816.SW_INS_NOT_SUPPORTED);
		}
	}

	private short
	recvRamBuf(APDU apdu)
	{
		final byte[] buffer = apdu.getBuffer();
		short recvLen = apdu.setIncomingAndReceive();
		final short cdata = apdu.getOffsetCdata();

		while (recvLen > 0) {
			if (RAM_BUF_SIZE <
			    (short)(rbStat[RBSTAT_RECV_OFF] + recvLen)) {
				ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
				return ((short)0);
			}
			Util.arrayCopy(buffer, cdata,
			    ramBuf, rbStat[RBSTAT_RECV_OFF], recvLen);
			rbStat[RBSTAT_RECV_OFF] += recvLen;
			recvLen = apdu.receiveBytes(cdata);
		}

		if (apdu.isCommandChainingCLA()) {
			ISOException.throwIt(ISO7816.SW_NO_ERROR);
			return ((short)0);
		}

		recvLen = rbStat[RBSTAT_RECV_OFF];
		rbStat[RBSTAT_RECV_OFF] = (short)0;
		return (recvLen);
	}

	private void
	sendRamBuf(APDU apdu, short len)
	{
		short le = apdu.setOutgoing();

		short toSend = len;
		if (toSend > le)
			toSend = le;
		if (toSend > (short)0xFF)
			toSend = (short)0xFF;

		final short rem = (short)(len - toSend);
		final byte wantNext =
		    rem > (short)0xFF ? (byte)0xFF : (byte)rem;

		apdu.setOutgoingLength(toSend);
		apdu.sendBytesLong(ramBuf, (short)0, toSend);
		if (rem > 0) {
			rbStat[RBSTAT_SEND_LEN] = len;
			rbStat[RBSTAT_SEND_OFF] = toSend;
			ISOException.throwIt(
			    (short)(ISO7816.SW_BYTES_REMAINING_00 |
			    ((short)wantNext & (short)0x00ff)));
		} else {
			ISOException.throwIt(ISO7816.SW_NO_ERROR);
		}
	}

	private void
	continueResponse(APDU apdu)
	{
		short rem =
		    (short)(rbStat[RBSTAT_SEND_LEN] - rbStat[RBSTAT_SEND_OFF]);
		if (rbStat[RBSTAT_SEND_LEN] < 1 || rem < 1) {
			ISOException.throwIt(
			    ISO7816.SW_CONDITIONS_NOT_SATISFIED);
			return;
		}

		final short le = apdu.setOutgoing();

		short toSend = rem;
		if (toSend > le)
			toSend = le;
		if (toSend > (short)0xFF)
			toSend = (short)0xFF;

		rem = (short)(rem - toSend);
		final byte wantNext =
		    rem > (short)0xFF ? (byte)0xFF : (byte)rem;

		apdu.setOutgoingLength(toSend);
		apdu.sendBytesLong(ramBuf, rbStat[RBSTAT_SEND_OFF], toSend);

		rbStat[RBSTAT_SEND_OFF] += toSend;

		if (wantNext > (byte)0) {
			ISOException.throwIt(
			    (short)(ISO7816.SW_BYTES_REMAINING_00 |
			    ((short)wantNext & (short)0x00ff)));
		} else {
			rbStat[RBSTAT_SEND_LEN] = (short)0;
		}
	}

	private void
	processGenAsym(APDU apdu)
	{
		final byte[] buffer = apdu.getBuffer();
		short lc, len, cLen;
		byte tag, alg;
		PivSlot slot;

		if (buffer[ISO7816.OFFSET_P1] != (byte)0x00) {
			ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
			return;
		}

		switch (buffer[ISO7816.OFFSET_P2]) {
		case (byte)0x9A:
			slot = slot9a;
			break;
		case (byte)0x9C:
			slot = slot9c;
			break;
		case (byte)0x9D:
			slot = slot9d;
			break;
		case (byte)0x9E:
			slot = slot9e;
			break;
		default:
			ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
			return;
		}

		lc = apdu.setIncomingAndReceive();
		if (lc != apdu.getIncomingLength()) {
			ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
			return;
		}

		if (!slot9b.flags[PivSlot.F_UNLOCKED]) {
			ISOException.throwIt(
			    ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);
			return;
		}

		tlv.setTarget(null, apdu.getOffsetCdata(), lc);

		if (tlv.readTag() != (byte)0xAC ||
		    tlv.readTag() != (byte)0x80 ||
		    tlv.tagLength() != (short)1) {
			ISOException.throwIt(ISO7816.SW_WRONG_DATA);
			return;
		}
		alg = tlv.readByte();
		if (!tlv.atEnd()) {
			if (tlv.readTag() != (byte)0x81) {
				ISOException.throwIt(ISO7816.SW_WRONG_DATA);
				return;
			}
			tlv.skip();
			if (!tlv.atEnd()) {
				ISOException.throwIt(ISO7816.SW_WRONG_DATA);
				return;
			}
		}

		switch (alg) {
		case PIV_ALG_RSA1024:
			if (slot.asym == null || slot.asymAlg != alg) {
				slot.asym = new KeyPair(KeyPair.ALG_RSA_CRT,
				    (short)1024);
			}
			slot.asymAlg = alg;
			break;
		case PIV_ALG_RSA2048:
			if (slot.asym == null || slot.asymAlg != alg) {
				slot.asym = new KeyPair(KeyPair.ALG_RSA_CRT,
				    (short)2048);
			}
			slot.asymAlg = alg;
			break;
		default:
			ISOException.throwIt(ISO7816.SW_WRONG_DATA);
			return;
		}

		slot.asym.genKeyPair();

		tlv.setTarget(ramBuf);

		tlv.push64k((short)0x7F49);

		switch (alg) {
		case PIV_ALG_RSA1024:
		case PIV_ALG_RSA2048:
			RSAPublicKey pubk = (RSAPublicKey)slot.asym.getPublic();

			tlv.push64k((byte)0x81);
			cLen = pubk.getModulus(ramBuf, tlv.offset());
			tlv.write(cLen);
			tlv.pop();

			tlv.push((byte)0x82);
			cLen = pubk.getExponent(ramBuf, tlv.offset());
			tlv.write(cLen);
			tlv.pop();
			break;
		default:
			return;
		}

		len = tlv.pop();

		sendRamBuf(apdu, len);
	}

	private void
	processGeneralAuth(APDU apdu)
	{
		final byte[] buffer = apdu.getBuffer();
		final byte key;
		byte alg, tag, wanted = 0;
		short lc, le, len, cLen;
		final PivSlot slot;
		final Cipher ci;

		alg = buffer[ISO7816.OFFSET_P1];
		key = buffer[ISO7816.OFFSET_P2];

		lc = recvRamBuf(apdu);
		if (lc == (short)0) {
			return;
		}

		tlv.setTarget(ramBuf, (short)0, lc);

		if (tlv.readTag() != (byte)0x7C) {
			ISOException.throwIt(ISO7816.SW_WRONG_DATA);
			return;
		}

		switch (key) {
		case (byte)0x9a:
			slot = slot9a;
			break;
		case (byte)0x9b:
			slot = slot9b;
			break;
		case (byte)0x9c:
			slot = slot9c;
			break;
		case (byte)0x9d:
			slot = slot9d;
			break;
		case (byte)0x9e:
			slot = slot9e;
			break;
		default:
			ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
			return;
		}

		switch (alg) {
		case PIV_ALG_DEFAULT:
		case PIV_ALG_3DES:
			alg = PIV_ALG_3DES;
			if (slot.symAlg != alg || slot.sym == null) {
				ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
				return;
			}
			len = (short)8;
			break;
		case PIV_ALG_AES128:
			if (slot.symAlg != alg || slot.sym == null) {
				ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
				return;
			}
			len = (short)16;
			break;
		case PIV_ALG_RSA1024:
		case PIV_ALG_RSA2048:
			if (slot.asymAlg != alg || slot.asym == null) {
				ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
				return;
			}
			len = (short)0;
			break;
		default:
			ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
			return;
		}

		switch (alg) {
		case PIV_ALG_3DES:
			ci = tripleDes;
			break;
		case PIV_ALG_AES128:
			ci = aes128;
			break;
		case PIV_ALG_RSA1024:
		case PIV_ALG_RSA2048:
			ci = rsaPkcs1;
			break;
		default:
			ci = null;
			break;
		}

		/*
		 * First, scan through the TLVs to figure out what the host
		 * actually wants from us.
		 */
		while (!tlv.atEnd()) {
			tag = tlv.readTag();
			if (tlv.tagLength() == 0) {
				wanted = tag;
				break;
			}
			tlv.skip();
		}

		/* Now rewind, let's figure out what to do */
		tlv.setTarget(ramBuf, (short)0, lc);
		tlv.readTag(); /* The 0x7C outer tag */

		tag = tlv.readTag();
		if (tag == wanted) {
			tlv.skip();
			if (!tlv.atEnd())
				tag = tlv.readTag();
		}

		if (wanted == (byte)0) {
			if (key != (byte)0x9b) {
				ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
				return;
			}

			byte comp = -1;
			if (tag == GA_TAG_RESPONSE) {
				ci.init(slot.sym, Cipher.MODE_DECRYPT, iv,
				    (short)0, len);
				cLen = ci.doFinal(ramBuf, tlv.offset(), len,
				    ramBuf, lc);

				if (tlv.tagLength() == cLen) {
					comp = Util.arrayCompare(ramBuf, lc,
					    challenge, (short)0, cLen);
				}

			} else if (tag == GA_TAG_WITNESS) {
				if (tlv.tagLength() == len) {
					comp = Util.arrayCompare(ramBuf,
					    tlv.offset(), challenge,
					    (short)0, len);
				}

			} else {
				ISOException.throwIt(ISO7816.SW_WRONG_DATA);
				return;
			}

			if (comp == 0) {
				slot.flags[PivSlot.F_UNLOCKED] = true;
			} else {
				ISOException.throwIt(ISO7816.SW_WRONG_DATA);
				return;
			}

			tlv.skip();
			if (tlv.atEnd()) {
				ISOException.throwIt(ISO7816.SW_NO_ERROR);
				return;
			}
			tag = tlv.readTag();
			if (tag == GA_TAG_CHALLENGE)
				wanted = GA_TAG_RESPONSE;
		}

		switch (wanted) {
		case GA_TAG_CHALLENGE:
			if (key != (byte)0x9b) {
				ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
				return;
			}
			/*
			 * The host is asking us for a challenge value
			 * for them to encrypt and return in a RESPONSE
			 */
			le = apdu.setOutgoing();
			tlv.setTarget(null);

			randData.generateData(challenge, (short)0, len);
			/*for (byte i = 0; i < (byte)len; ++i)
				challenge[i] = (byte)(i + 1);*/

			tlv.push((byte)0x7C);

			tlv.push(GA_TAG_CHALLENGE);
			tlv.write(challenge, (short)0, len);
			tlv.pop();

			len = tlv.pop();

			len = le > len ? len : le;
			apdu.setOutgoingLength(len);
			apdu.sendBytes((short)0, len);
			break;

		case GA_TAG_WITNESS:
			if (key != (byte)0x9b) {
				ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
				return;
			}
			le = apdu.setOutgoing();
			tlv.setTarget(null);

			randData.generateData(challenge, (short)0, len);

			tlv.push((byte)0x7C);

			tlv.push(GA_TAG_WITNESS);

			ci.init(slot.sym, Cipher.MODE_ENCRYPT, iv,
			    (short)0, len);
			cLen = ci.doFinal(challenge, (short)0, len, buffer,
			    tlv.offset());
			tlv.write(cLen);
			tlv.pop();

			len = tlv.pop();

			len = le > len ? len : le;
			apdu.setOutgoingLength(len);
			apdu.sendBytes((short)0, len);
			break;

		case GA_TAG_RESPONSE:
			if (tag != GA_TAG_CHALLENGE) {
				ISOException.throwIt(ISO7816.SW_WRONG_DATA);
				return;
			}
			cLen = tlv.tagLength();
			switch (alg) {
			case PIV_ALG_RSA1024:
				cLen = (short)256;
				break;
			case PIV_ALG_RSA2048:
				cLen = (short)512;
				break;
			}
			if ((short)(RAM_BUF_SIZE - cLen) < lc) {
				ISOException.throwIt(ISO7816.SW_FILE_FULL);
				return;
			}
			lc = (short)(RAM_BUF_SIZE - cLen);

			if ((slot.needsPin && !slot.pin.isValidated()) ||
			    (key == (byte)0x9b &&
			    !slot9b.flags[PivSlot.F_UNLOCKED])) {
				ISOException.throwIt(
				    ISO7816.
				    SW_SECURITY_STATUS_NOT_SATISFIED);
				return;
			}

			if (slot.symAlg == alg) {
				ci.init(slot.sym, Cipher.MODE_ENCRYPT,
				    iv, (short)0, len);
				cLen = ci.doFinal(ramBuf, tlv.offset(),
				    tlv.tagLength(), ramBuf, lc);

				tlv.setTarget(ramBuf);

				tlv.push((byte)0x7C, (short)(cLen + 4));
				tlv.push(GA_TAG_RESPONSE, cLen);
				tlv.write(ramBuf, lc, cLen);
				tlv.pop();

				len = tlv.pop();
				sendRamBuf(apdu, len);

			} else if (slot.asymAlg == alg && (
			    alg == PIV_ALG_RSA1024 || alg == PIV_ALG_RSA2048)) {
				ci.init(slot.asym.getPrivate(),
				    Cipher.MODE_ENCRYPT);
				cLen = ci.doFinal(ramBuf, tlv.offset(),
				    tlv.tagLength(), ramBuf, lc);

				tlv.setTarget(ramBuf);

				tlv.push((byte)0x7C, (short)(cLen + 4));
				tlv.push(GA_TAG_RESPONSE, cLen);
				tlv.write(ramBuf, lc, cLen);
				tlv.pop();

				len = tlv.pop();
				sendRamBuf(apdu, len);
			}
			break;

		default:
			ISOException.throwIt(ISO7816.SW_WRONG_DATA);
			return;
		}
	}

	private void
	processPutData(APDU apdu)
	{
		final byte[] buffer = apdu.getBuffer();
		short lc;
		byte tag;
		PivSlot slot;

		if (buffer[ISO7816.OFFSET_P1] != (byte)0x3F ||
		    buffer[ISO7816.OFFSET_P2] != (byte)0xFF) {
			ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
			return;
		}

		lc = recvRamBuf(apdu);
		if (lc == (short)0) {
			return;
		}

		tlv.setTarget(ramBuf, (short)0, lc);

		if (tlv.readTag() != (byte)0x5C) {
			ISOException.throwIt(ISO7816.SW_WRONG_DATA);
			return;
		}

		if (tlv.tagLength() == (short)3 &&
		    tlv.readByte() == (byte)0x5F &&
		    tlv.readByte() == (byte)0xC1) {
			/* A regular PIV object, so let's go find the data. */
			tag = tlv.readByte();
			switch (tag) {
			case TAG_CHUID:
				ISOException.throwIt(
				    ISO7816.SW_FUNC_NOT_SUPPORTED);
				return;
			case TAG_CERT_9A:
				slot = slot9a;
				break;
			case TAG_CERT_9C:
				slot = slot9c;
				break;
			case TAG_CERT_9D:
				slot = slot9d;
				break;
			case TAG_CERT_9E:
				slot = slot9e;
				break;
			default:
				ISOException.throwIt(
				    ISO7816.SW_FUNC_NOT_SUPPORTED);
				return;
			}

			if (tlv.readTag() != (byte)0x53) {
				ISOException.throwIt(ISO7816.SW_WRONG_DATA);
				return;
			}

			if (slot.cert == null)
				slot.cert = new byte[MAX_CERT_SIZE];
			slot.certGzip = false;

			while (!tlv.atEnd()) {
				tag = tlv.readTag();
				if (tag == (byte)0x70) {
					slot.certLen = tlv.read(slot.cert,
					    (short)0, MAX_CERT_SIZE);
				} else if (tag == (byte)0x71) {
					if (tlv.readByte() == (byte)0x01)
						slot.certGzip = true;
					tlv.skip();
				} else {
					tlv.skip();
				}
			}

		} else {
			ISOException.throwIt(ISO7816.SW_FUNC_NOT_SUPPORTED);
		}
	}

	private void
	processVerify(APDU apdu)
	{
		final byte[] buffer = apdu.getBuffer();
		short lc, pinOff;
		OwnerPIN pin;

		if (buffer[ISO7816.OFFSET_P1] != (byte)0x00 &&
		    buffer[ISO7816.OFFSET_P1] != (byte)0xFF) {
			ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
			return;
		}

		switch (buffer[ISO7816.OFFSET_P2]) {
		case (byte)0x80:
			pin = pivPin;
			break;
		case (byte)0x81:
			pin = pukPin;
			break;
		default:
			ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
			return;
		}

		lc = apdu.setIncomingAndReceive();
		if (lc != apdu.getIncomingLength()) {
			ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
			return;
		}
		pinOff = apdu.getOffsetCdata();

		if (lc != 8) {
			ISOException.throwIt(ISO7816.SW_WRONG_DATA);
			return;
		}

		if (!pin.check(buffer, pinOff, (byte)8)) {
			ISOException.throwIt((short)(
			    (short)0x63C0 | pin.getTriesRemaining()));
			return;
		}
	}

	private void
	processChangePin(APDU apdu)
	{
		final byte[] buffer = apdu.getBuffer();
		short lc, oldPinOff, newPinOff, idx;
		OwnerPIN pin;

		if (buffer[ISO7816.OFFSET_P1] != (byte)0x00) {
			ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
			return;
		}

		switch (buffer[ISO7816.OFFSET_P2]) {
		case (byte)0x80:
			pin = pivPin;
			break;
		case (byte)0x81:
			pin = pukPin;
			break;
		default:
			ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
			return;
		}

		lc = apdu.setIncomingAndReceive();
		if (lc != apdu.getIncomingLength()) {
			ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
			return;
		}

		oldPinOff = apdu.getOffsetCdata();
		if (lc != 16) {
			ISOException.throwIt(ISO7816.SW_WRONG_DATA);
			return;
		}

		if (!pin.isValidated() &&
		    !slot9b.flags[PivSlot.F_UNLOCKED] &&
		    !pin.check(buffer, oldPinOff, (byte)8)) {
			ISOException.throwIt((short)(
			    (short)0x63C0 | pin.getTriesRemaining()));
			return;
		}

		newPinOff = (short)(oldPinOff + 8);
		for (idx = newPinOff; idx < (short)(newPinOff + 6); ++idx) {
			if (buffer[idx] < (byte)0x30 ||
			    buffer[idx] > (byte)0x39) {
				ISOException.throwIt(ISO7816.SW_WRONG_DATA);
				return;
			}
		}
		for (; idx < (short)(newPinOff + 8); ++idx) {
			if (buffer[idx] != (byte)0xFF && (
			    buffer[idx] < (byte)0x30 ||
			    buffer[idx] > (byte)0x39)) {
				ISOException.throwIt(ISO7816.SW_WRONG_DATA);
				return;
			}
		}
		pin.update(buffer, newPinOff, (byte)8);
	}

	private void
	processGetData(APDU apdu)
	{
		final byte[] buffer = apdu.getBuffer();
		short lc;
		byte tag;

		if (buffer[ISO7816.OFFSET_P1] != (byte)0x3F ||
		    buffer[ISO7816.OFFSET_P2] != (byte)0xFF) {
			ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
			return;
		}

		lc = apdu.setIncomingAndReceive();
		if (lc != apdu.getIncomingLength()) {
			ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
			return;
		}

		tlv.setTarget(null, apdu.getOffsetCdata(), lc);

		tag = tlv.readTag();
		if (tag != (byte)0x5C) {
			ISOException.throwIt(ISO7816.SW_WRONG_DATA);
			return;
		}

		if (tlv.tagLength() == (short)3 &&
		    tlv.readByte() == (byte)0x5F &&
		    tlv.readByte() == (byte)0xC1) {
			/* A regular PIV object, so let's go find the data. */
			tag = tlv.readByte();
			sendPIVObject(apdu, tag);

		} else if (tlv.tagLength() == 1 &&
		    tlv.readByte() == (byte)0x7E) {
			/* The special discovery object */
			sendDiscoveryObject(apdu);

		} else {
			ISOException.throwIt(ISO7816.SW_FILE_NOT_FOUND);
		}
	}

	private void
	sendPIVObject(APDU apdu, byte tag)
	{
		short le, len;
		PivSlot slot;

		switch (tag) {
		case TAG_CARDCAP:
			le = apdu.setOutgoing();
			tlv.setTarget(null);

			tlv.push((byte)0x53);

			/* Card Identifier */
			tlv.push((byte)0xF0);
			tlv.pop();

			/* Container version number */
			tlv.push((byte)0xF1);
			tlv.pop();

			/* Data Model Number */
			tlv.push((byte)0xF5);
			tlv.writeByte((byte)0x10);
			tlv.pop();

			len = tlv.pop();

			len = le > len ? len : le;
			apdu.setOutgoingLength(len);
			apdu.sendBytes((short)0, len);
			return;
		case TAG_CHUID:
			le = apdu.setOutgoing();
			tlv.setTarget(null);

			tlv.push((byte)0x53);

			/* FASC-N identifier */
			tlv.push((byte)0x30);
			tlv.write(fascn, (short)0, (short)fascn.length);
			tlv.pop();

			/* Card GUID */
			tlv.push((byte)0x34);
			tlv.write(guid, (short)0, (short)guid.length);
			tlv.pop();

			/* Expiry date */
			tlv.push((byte)0x35);
			tlv.write(expiry, (short)0, (short)expiry.length);
			tlv.pop();

			len = tlv.pop();

			len = le > len ? len : le;
			apdu.setOutgoingLength(len);
			apdu.sendBytes((short)0, len);
			return;
		case TAG_CERT_9A:
			slot = slot9a;
			break;
		case TAG_CERT_9C:
			slot = slot9c;
			break;
		case TAG_CERT_9D:
			slot = slot9d;
			break;
		case TAG_CERT_9E:
			slot = slot9e;
			break;
		default:
			ISOException.throwIt(ISO7816.SW_FILE_NOT_FOUND);
			return;
		}

		if (slot.cert == null || slot.certLen == 0) {
			ISOException.throwIt(ISO7816.SW_FILE_NOT_FOUND);
			return;
		}

		tlv.setTarget(ramBuf);

		tlv.push((byte)0x53, (short)(slot.certLen + 10));

		tlv.push((byte)0x70, slot.certLen);
		tlv.write(slot.cert, (short)0, slot.certLen);
		tlv.pop();

		tlv.push((byte)0x71);
		if (slot9a.certGzip)
			tlv.writeByte((byte)0x01);
		else
			tlv.writeByte((byte)0x00);
		tlv.pop();

		len = tlv.pop();

		sendRamBuf(apdu, len);
	}

	private void
	sendDiscoveryObject(APDU apdu)
	{
		short le, len;

		le = apdu.setOutgoing();
		tlv.setTarget(null);

		tlv.push((byte)0x7E);

		/* AID */
		tlv.push((byte)0x4F);
		tlv.write(PIV_AID, (short)0, (short)PIV_AID.length);
		tlv.pop();

		/* PIN policy */
		tlv.push((short)0x5F2F);
		tlv.writeByte((byte)0x40);	/* PIV pin only, no others */
		tlv.writeByte((byte)0x00);	/* RFU, since no global PIN */
		tlv.pop();

		len = tlv.pop();

		len = le > len ? len : le;
		apdu.setOutgoingLength(len);
		apdu.sendBytes((short)0, len);
	}
}
