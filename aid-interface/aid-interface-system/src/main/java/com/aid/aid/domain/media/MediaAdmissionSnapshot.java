package com.aid.aid.domain.media;

import lombok.Data;

/** Read-only aggregate of the same permits used by admission; never grants execution. */
@Data
public class MediaAdmissionSnapshot {
    private Long globalActive;
    private Long userActive;
    private Long modelActive;
    private Long providerActive;
    private Long globalUncertain;
    private Long userUncertain;
    private Long modelUncertain;
    private Long providerUncertain;
    private Long ownUncertain;
}
