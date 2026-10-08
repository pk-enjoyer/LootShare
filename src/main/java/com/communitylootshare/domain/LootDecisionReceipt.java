/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.communitylootshare.domain;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import lombok.EqualsAndHashCode;

/** A host signature over the immutable contribution and its complete frozen decision. */
@EqualsAndHashCode
public final class LootDecisionReceipt
{
	private final long signerMemberId;
	private final String publicKey;
	private final String signature;

	public LootDecisionReceipt(long signerMemberId, String publicKey, String signature)
	{
		if (signerMemberId <= 0L || publicKey == null || publicKey.isEmpty() || publicKey.length() > 256
			|| signature == null || signature.isEmpty() || signature.length() > 256)
		{
			throw new IllegalArgumentException("Invalid decision receipt");
		}
		Base64.getDecoder().decode(publicKey);
		Base64.getDecoder().decode(signature);
		this.signerMemberId = signerMemberId;
		this.publicKey = publicKey;
		this.signature = signature;
	}

	public static KeyPair generateKey() throws GeneralSecurityException
	{
		KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
		generator.initialize(new ECGenParameterSpec("secp256r1"));
		return generator.generateKeyPair();
	}

	public static String keyId(String publicKey, long signerMemberId)
	{
		return publicKey + "#" + signerMemberId;
	}

	public static boolean isValidKeyId(String id, long signerMemberId)
	{
		if (id == null)
		{
			return false;
		}
		int separator = id.indexOf('#');
		return separator < 0 ? isValidPublicKey(id)
			: id.equals(keyId(id.substring(0, separator), signerMemberId))
				&& isValidPublicKey(id.substring(0, separator));
	}

	public boolean isTrustedBy(Map<String, Long> keys)
	{
		return Long.valueOf(signerMemberId).equals(keys.get(keyId(publicKey, signerMemberId)))
			|| Long.valueOf(signerMemberId).equals(keys.get(publicKey));
	}

	public static String publicKey(KeyPair key)
	{
		return Base64.getEncoder().encodeToString(key.getPublic().getEncoded());
	}

	public static boolean isValidPublicKey(String encoded)
	{
		if (encoded == null || encoded.isEmpty() || encoded.length() > 256)
		{
			return false;
		}
		try
		{
			KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
			return true;
		}
		catch (GeneralSecurityException | IllegalArgumentException e)
		{
			return false;
		}
	}

	public static LootDecisionReceipt sign(long hostMemberId, KeyPair key, LootProposal proposal)
		throws GeneralSecurityException
	{
		Signature signer = Signature.getInstance("SHA256withECDSA");
		signer.initSign(key.getPrivate());
		signer.update(payload(hostMemberId, proposal));
		return new LootDecisionReceipt(hostMemberId, publicKey(key),
			Base64.getEncoder().encodeToString(signer.sign()));
	}

	public boolean verifies(LootProposal proposal)
	{
		try
		{
			Signature verifier = Signature.getInstance("SHA256withECDSA");
			verifier.initVerify(KeyFactory.getInstance("EC").generatePublic(
				new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey))));
			verifier.update(payload(signerMemberId, proposal));
			return verifier.verify(Base64.getDecoder().decode(signature));
		}
		catch (GeneralSecurityException | IllegalArgumentException e)
		{
			return false;
		}
	}

	/** Commits to the complete frozen decision, independently of a replaceable signature. */
	public static String decisionId(LootProposal proposal)
	{
		try
		{
			return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(payload(0L, proposal)));
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException("SHA-256 is unavailable", e);
		}
	}

	private static byte[] payload(long signer, LootProposal proposal)
	{
		if (proposal == null || proposal.getStatus() == LootProposalStatus.PENDING)
		{
			throw new IllegalArgumentException("A final proposal is required");
		}
		try
		{
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			DataOutputStream output = new DataOutputStream(bytes);
			output.writeUTF("community-lootshare-decision-v1");
			output.writeLong(signer);
			output.writeLong(proposal.getPartyId());
			output.writeLong(proposal.getOwnerMemberId());
			SharedLootEvent event = proposal.getEvent();
			output.writeUTF(event.getProposalId());
			output.writeUTF(event.getRecipient());
			output.writeUTF(event.getSourceLabel());
			output.writeLong(event.getCapturedAt().toEpochMilli());
			output.writeInt(event.getItems().size());
			for (SharedLootItem item : event.getItems())
			{
				output.writeInt(item.getItemId());
				output.writeInt(item.getPricingId());
				output.writeLong(item.getQuantity());
				output.writeLong(item.getUnitPrice());
			}
			output.writeUTF(proposal.getStatus().name());
			output.writeLong(proposal.getDecidedAt().toEpochMilli());
			output.writeInt(proposal.getParticipants().size());
			for (LootshareParticipant participant : proposal.getParticipants())
			{
				output.writeLong(participant.getMemberId());
				output.writeUTF(participant.getDisplayName());
			}
			output.flush();
			return bytes.toByteArray();
		}
		catch (IOException e)
		{
			throw new IllegalStateException("Unable to encode decision receipt", e);
		}
	}

	/** Domain-separated history payloads include their signer identity in the signature. */
	public static LootDecisionReceipt signBytes(long memberId, KeyPair key, byte[] payload) throws GeneralSecurityException
	{
		Signature signer = Signature.getInstance("SHA256withECDSA");
		signer.initSign(key.getPrivate());
		signer.update(java.nio.ByteBuffer.allocate(Long.BYTES).putLong(memberId).array());
		signer.update(payload);
		return new LootDecisionReceipt(memberId, publicKey(key), Base64.getEncoder().encodeToString(signer.sign()));
	}

	public boolean verifiesBytes(byte[] payload)
	{
		try
		{
			Signature verifier = Signature.getInstance("SHA256withECDSA");
			verifier.initVerify(KeyFactory.getInstance("EC").generatePublic(
				new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey))));
			verifier.update(java.nio.ByteBuffer.allocate(Long.BYTES).putLong(signerMemberId).array());
			verifier.update(payload);
			return verifier.verify(Base64.getDecoder().decode(signature));
		}
		catch (GeneralSecurityException | IllegalArgumentException e) { return false; }
	}

	public LootDecisionReceipt validatedCopy()
	{
		return new LootDecisionReceipt(signerMemberId, publicKey, signature);
	}

	public long getSignerMemberId() { return signerMemberId; }
	public String getPublicKey() { return publicKey; }
}
