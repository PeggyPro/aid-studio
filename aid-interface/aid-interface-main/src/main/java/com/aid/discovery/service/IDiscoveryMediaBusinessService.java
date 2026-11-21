package com.aid.discovery.service;

import com.aid.discovery.dto.AdminDiscoveryPageRequest;
import com.aid.discovery.dto.AdminDiscoveryReviewRequest;
import com.aid.discovery.dto.AdminDiscoverySaveRequest;
import com.aid.discovery.dto.AdminDiscoveryUpdateRequest;
import com.aid.discovery.dto.DiscoveryPageRequest;
import com.aid.discovery.dto.UserDiscoveryPageRequest;
import com.aid.discovery.dto.UserDiscoveryResubmitRequest;
import com.aid.discovery.dto.UserDiscoverySubmitRequest;
import com.aid.discovery.vo.DiscoveryManageVO;
import com.aid.discovery.vo.DiscoveryPageResult;
import com.aid.discovery.vo.DiscoveryPublicVO;

/** Discovery publishing, submission and review business boundary. */
public interface IDiscoveryMediaBusinessService
{
    DiscoveryPageResult<DiscoveryPublicVO> pagePublic(DiscoveryPageRequest request);

    DiscoveryPublicVO getPublic(Long id);

    Long submitUser(UserDiscoverySubmitRequest request, Long userId, String username);

    DiscoveryPageResult<DiscoveryManageVO> pageUser(UserDiscoveryPageRequest request, Long userId);

    DiscoveryManageVO getUser(Long id, Long userId);

    void resubmitUser(UserDiscoveryResubmitRequest request, Long userId, String username);

    void deleteUser(Long id, Long userId, String username);

    DiscoveryPageResult<DiscoveryManageVO> pageAdmin(AdminDiscoveryPageRequest request);

    DiscoveryManageVO getAdmin(Long id);

    Long createAdmin(AdminDiscoverySaveRequest request, Long operatorId, String operatorName);

    void updateAdmin(AdminDiscoveryUpdateRequest request, Long operatorId, String operatorName);

    void reviewAdmin(AdminDiscoveryReviewRequest request, Long operatorId, String operatorName);

    void deleteAdmin(Long id, Long operatorId, String operatorName);
}
