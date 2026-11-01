package com.restofy.order.application.compat.waiter;

import com.restofy.order.application.compat.waiter.WaiterCompat.WaiterOrderCompat;
import com.restofy.order.application.compat.waiter.WaiterMapper.CancellationData;
import com.restofy.order.domain.entity.Order;
import com.restofy.order.infrastructure.persistence.CancellationStore;
import com.restofy.order.infrastructure.persistence.CancellationStore.Row;
import com.restofy.order.infrastructure.persistence.PaymentCaptureStore;
import com.restofy.order.infrastructure.persistence.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds the Waiter-shaped money and cancellation projection of one order, inside the order's own restaurant (an order of
 * another restaurant is simply absent). Read-only. It is not an endpoint and decides nothing about who may call it: the
 * future caller (a service endpoint on the internal trust boundary) is responsible for authentication, which is a
 * separate piece of work.
 */
@Service
public class WaiterCompatibilityService {

    private final OrderRepository orders;
    private final CancellationStore cancellations;
    private final PaymentCaptureStore captures;

    public WaiterCompatibilityService(OrderRepository orders, CancellationStore cancellations,
                                      PaymentCaptureStore captures) {
        this.orders = orders;
        this.cancellations = cancellations;
        this.captures = captures;
    }

    /**
     * @throws IllegalArgumentException if the order's currency has no usable minor unit (nothing is guessed)
     * @throws IllegalStateException    if the stored figures contradict each other
     */
    @Transactional(readOnly = true)
    public Optional<WaiterOrderCompat> forOrder(UUID restaurantId, UUID orderId) {
        return orders.findByIdAndRestaurantId(orderId, restaurantId).map(this::project);
    }

    private WaiterOrderCompat project(Order order) {
        List<Row> rows = cancellations.findByOrder(order.getRestaurantId(), order.getId());
        OrderFinance.Breakdown finance = OrderFinance.compute(order, rows, captures.appliedAmount(order.getId()));
        List<CancellationData> data = rows.stream().map(row -> new CancellationData(row,
                cancellations.findItems(row.id()), cancellations.findReasons(row.id()))).toList();
        return new WaiterOrderCompat(order.getId(), order.getRestaurantId(), WaiterMapper.money(finance),
                WaiterMapper.items(order), WaiterMapper.cancellations(data), finance);
    }
}
