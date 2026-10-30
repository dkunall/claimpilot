package com.claimpilot.claim.application;

import com.claimpilot.claim.domain.ApprovalChain;
import com.claimpilot.product.domain.LineOfBusiness;

/** Port to the authority matrix (approval_limit). */
public interface ApprovalLimits {

    ApprovalChain.Limits forLine(LineOfBusiness line);
}
