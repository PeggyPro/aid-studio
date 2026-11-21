package com.aid.director;

import lombok.Data;
import java.util.LinkedHashMap;
import java.util.Map;

/** 有期限的配对与协商权威状态。 */
@Data
public class DirectorRoom {
    private String id;
    private String epoch;
    private Long hostUserId;
    private Long projectId;
    private Long workspaceId;
    private Long sceneRowId;
    private String sceneKey;
    private Long cameraRowId;
    private String cameraKey;
    private Long fence;
    private long expiresAt;
    private long createdAt;
    private String state = "WAITING";
    private long version;
    private long bindingEpoch = 1;
    private long peerEpoch;
    private String negotiationId;
    private String peerNonce;
    private long negotiationUntil;
    private String negotiationRequestId;
    private String pairingHash;
    private long pairingUntil;
    private String guestId;
    private String grantHash;
    private long guestReservedUntil;
    private String hostConnection;
    private String hostInstance;
    private long hostSeenAt;
    private String guestConnection;
    private String guestInstance;
    private long guestSeenAt;
    private boolean hostReady;
    private boolean guestReady;
    private boolean offerReceived;
    private boolean answerReceived;
    private int hostCandidates;
    private int guestCandidates;
    private boolean controlAuthorized;
    private long leaseSeq;
    private long leaseUntil;
    private String createHash;
    private Map<String, String> signalHashes = new LinkedHashMap<>();
    private Map<String, String> actions = new LinkedHashMap<>();
}
