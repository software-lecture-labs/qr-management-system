package com.restofy.waiter.application.service;

import com.restofy.waiter.api.dto.response.PageResponse;
import com.restofy.waiter.api.dto.response.ServiceCallResponse;
import com.restofy.waiter.application.mapper.WaiterMapper;
import com.restofy.waiter.domain.entity.ServiceRequest;
import com.restofy.waiter.domain.exception.ResourceNotFoundException;
import com.restofy.waiter.infrastructure.persistence.repository.NotificationDeliveries;
import com.restofy.waiter.infrastructure.persistence.repository.ServiceRequestRepository;
import com.restofy.waiter.infrastructure.security.AuthenticatedStaff;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Staff view of the calls. The data is Waiter's own, so listing needs neither QR nor Order: it keeps working
 * while they are down. Each call carries the Notification synchronization state next to its business status.
 */
@Service
public class CallQueryService {

    private final ServiceRequestRepository calls;
    private final NotificationDeliveries deliveries;

    public CallQueryService(ServiceRequestRepository calls, NotificationDeliveries deliveries) {
        this.calls = calls;
        this.deliveries = deliveries;
    }

    public PageResponse<ServiceCallResponse> list(AuthenticatedStaff staff, CallFilter filter, PageParams page) {
        UUID restaurantId = AuthenticatedStaff.requireWaiterRestaurant(staff);
        Page<ServiceRequest> found = calls.findAll(ServiceCallSpecifications.matching(restaurantId, filter),
                PageRequest.of(page.page(), page.size(), filter.sort().toSort()));
        return PageResponse.of(present(restaurantId, found.getContent()), page.page(), page.size(), found.getTotalElements());
    }

    public ServiceCallResponse get(AuthenticatedStaff staff, UUID callId) {
        UUID restaurantId = AuthenticatedStaff.requireWaiterRestaurant(staff);
        ServiceRequest call = calls.findByIdAndRestaurantId(callId, restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("CALL_NOT_FOUND", "Call not found"));
        return present(restaurantId, List.of(call)).getFirst();
    }

    /** Response for a call the caller just changed. */
    public ServiceCallResponse present(UUID restaurantId, ServiceRequest call) {
        return present(restaurantId, List.of(call)).getFirst();
    }

    private List<ServiceCallResponse> present(UUID restaurantId, List<ServiceRequest> found) {
        Map<UUID, NotificationDeliveries.Sync> sync = deliveries.latestFor(restaurantId,
                found.stream().map(ServiceRequest::getId).toList());
        return found.stream().map(call -> WaiterMapper.toCall(call, sync.get(call.getId()))).toList();
    }
}
