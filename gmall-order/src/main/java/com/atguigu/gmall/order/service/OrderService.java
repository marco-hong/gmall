package com.atguigu.gmall.order.service;

import com.atguigu.core.bean.Resp;
import com.atguigu.core.bean.UserInfo;
import com.atguigu.core.exception.OrderException;
import com.atguigu.gmall.cart.pojo.Cart;
import com.atguigu.gmall.oms.entity.OrderEntity;
import com.atguigu.gmall.order.feign.*;
import com.atguigu.gmall.order.interceptors.LoginInterceptor;
import com.atguigu.gmall.order.vo.OrderConfirmVO;
import com.atguigu.gmall.oms.vo.OrderItemVO;
import com.atguigu.gmall.oms.vo.OrderSubmitVO;
import com.atguigu.gmall.pms.entity.SkuInfoEntity;
import com.atguigu.gmall.pms.entity.SkuSaleAttrValueEntity;
import com.atguigu.gmall.ums.entity.MemberEntity;
import com.atguigu.gmall.ums.entity.MemberReceiveAddressEntity;
import com.atguigu.gmall.wms.entity.WareSkuEntity;
import com.atguigu.gmall.wms.vo.SkuLockVO;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
public class OrderService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private GmallPmsClient pmsClient;

    @Autowired
    private GmallOmsClient omsClient;

    @Autowired
    private GmallCartClient cartClient;

    @Autowired
    private GmallUmsClient umsClient;

    @Autowired
    private GmallSmsClient smsClient;

    @Autowired
    private GmallWmsClient wmsClient;

    @Autowired
    private ThreadPoolExecutor threadPoolExecutor;

    @Autowired
    private AmqpTemplate amqpTemplate;

    private static final String TOKEN_PREFIX = "order:token:";

    public OrderConfirmVO confirm() {

        OrderConfirmVO orderConfirmVO = new OrderConfirmVO();

        UserInfo userInfo = LoginInterceptor.getUserInfo();
        Long userId = userInfo.getId();
        if (userId == null) {
            return null;
        }

//        List<CompletableFuture> futures = new ArrayList<>();

        CompletableFuture<Void> addressCompletableFuture = CompletableFuture.runAsync(() -> {
            // ???????????????????????????????????? ????????????id????????????????????????
            Resp<List<MemberReceiveAddressEntity>> addressResp = this.umsClient.queryAddressesByUserId(userId);
            List<MemberReceiveAddressEntity> memberReceiveAddressEntities = addressResp.getData();
            orderConfirmVO.setAddresses(memberReceiveAddressEntities);
        }, threadPoolExecutor);
//        futures.add(addressCompletableFuture);

        // ???????????????????????????????????????  skuId count
        CompletableFuture<Void> bigSkuCompletableFuture = CompletableFuture.supplyAsync(() -> {
            Resp<List<Cart>> cartsResp = this.cartClient.queryCheckedCartsByUserId(userId);
            List<Cart> cartList = cartsResp.getData();
            if (CollectionUtils.isEmpty(cartList)) {
                throw new OrderException("???????????????????????????");
            }
            return cartList;
        }, threadPoolExecutor).thenAcceptAsync(cartList -> {
            List<OrderItemVO> itemVOS = cartList.stream().map(cart -> {
                OrderItemVO orderItemVO = new OrderItemVO();
                Long skuId = cart.getSkuId();
                CompletableFuture<Void> skuCompletableFuture = CompletableFuture.runAsync(() -> {
                    Resp<SkuInfoEntity> skuInfoEntityResp = this.pmsClient.querySkuById(skuId);
                    SkuInfoEntity skuInfoEntity = skuInfoEntityResp.getData();
                    if (skuInfoEntity != null) {
                        orderItemVO.setWeight(skuInfoEntity.getWeight());
                        orderItemVO.setDefaultImage(skuInfoEntity.getSkuDefaultImg());
                        orderItemVO.setPrice(skuInfoEntity.getPrice());
                        orderItemVO.setTitle(skuInfoEntity.getSkuTitle());
                        orderItemVO.setSkuId(skuId);
                        orderItemVO.setCount(cart.getCount());
                    }
                });

                CompletableFuture<Void> saleAttrCompletableFuture = CompletableFuture.runAsync(() -> {
                    Resp<List<SkuSaleAttrValueEntity>> saleAttrValueResp = this.pmsClient.querySkuSaleAttrValuesBySkuId(skuId);
                    List<SkuSaleAttrValueEntity> attrValueEntities = saleAttrValueResp.getData();
                    orderItemVO.setSaleAttrValues(attrValueEntities);
                }, threadPoolExecutor);

                CompletableFuture<Void> wareSkuCompletableFuture = CompletableFuture.runAsync(() -> {
                    Resp<List<WareSkuEntity>> wareSkuResp = this.wmsClient.queryWareSkusBySkuId(skuId);
                    List<WareSkuEntity> wareSkuEntities = wareSkuResp.getData();
                    if (!CollectionUtils.isEmpty(wareSkuEntities)) {
                        orderItemVO.setStore(wareSkuEntities.stream().anyMatch(wareSkuEntity -> wareSkuEntity.getStock() > 0));
                    }
                }, threadPoolExecutor);

                CompletableFuture.allOf(skuCompletableFuture, saleAttrCompletableFuture, wareSkuCompletableFuture).join();
                return orderItemVO;
            }).collect(Collectors.toList());
            orderConfirmVO.setOrderItems(itemVOS);
        }, threadPoolExecutor);

        // ?????????????????????????????????
        CompletableFuture<Void> memberCompletableFuture = CompletableFuture.runAsync(() -> {
            Resp<MemberEntity> memberEntityResp = this.umsClient.queryMemberById(userId);
            MemberEntity memberEntity = memberEntityResp.getData();
            orderConfirmVO.setBounds(memberEntity.getIntegration());
        }, threadPoolExecutor);

        // ?????????????????????????????????????????????????????????????????????????????????????????????redis??????
        CompletableFuture<Void> tokenCompletableFuture = CompletableFuture.runAsync(() -> {
            String orderToken = IdWorker.getIdStr();
            orderConfirmVO.setOrderToken(orderToken);
            this.redisTemplate.opsForValue().set(TOKEN_PREFIX + orderToken, orderToken);
        }, threadPoolExecutor);

        CompletableFuture.allOf(addressCompletableFuture, bigSkuCompletableFuture, memberCompletableFuture, tokenCompletableFuture).join();

        return orderConfirmVO;
    }

    public OrderEntity submit(OrderSubmitVO submitVO) {

        UserInfo userInfo = LoginInterceptor.getUserInfo();

        // ??????orderToken
        String orderToken = submitVO.getOrderToken();

        // 1. ????????????????????????redis????????????orderToken??????????????????????????????????????????????????????redis??????orderToken
        String script = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
        Long flag = this.redisTemplate.execute(new DefaultRedisScript<>(script, Long.class), Arrays.asList(TOKEN_PREFIX + orderToken), orderToken);
        if (flag == 0) {
            throw new OrderException("???????????????????????????");
        }

        // 2. ?????????????????????????????????
        List<OrderItemVO> items = submitVO.getItems(); // ????????????
        BigDecimal totalPrice = submitVO.getTotalPrice(); // ??????
        if (CollectionUtils.isEmpty(items)) {
            throw new OrderException("?????????????????????????????????????????????????????????");
        }
        // ????????????????????????
        BigDecimal currentTotalPrice = items.stream().map(item -> {
            Resp<SkuInfoEntity> skuInfoEntityResp = this.pmsClient.querySkuById(item.getSkuId());
            SkuInfoEntity skuInfoEntity = skuInfoEntityResp.getData();
            if (skuInfoEntity != null) {
                return skuInfoEntity.getPrice().multiply(new BigDecimal(item.getCount()));
            }
            return new BigDecimal(0);
        }).reduce((a, b) -> a.add(b)).get();
        // ???????????????????????????????????????????????????
        if (currentTotalPrice.compareTo(totalPrice) != 0) {
            throw new OrderException("???????????????????????????????????????????????????");
        }


        // 3. ?????????????????????????????????????????????????????????????????????????????????????????? ???????????????????????????
        List<SkuLockVO> lockVOS = items.stream().map(orderItemVO -> {
            SkuLockVO skuLockVO = new SkuLockVO();
            skuLockVO.setSkuId(orderItemVO.getSkuId());
            skuLockVO.setCount(orderItemVO.getCount());
            skuLockVO.setOrderToken(orderToken);
            return skuLockVO;
        }).collect(Collectors.toList());
        Resp<Object> wareResp = this.wmsClient.checkAndLockStore(lockVOS);
        if (wareResp.getCode() != 0) {
            throw new OrderException(wareResp.getMsg());
        }

//        int i = 1 / 0;

        // 4. ??????????????????????????????????????? ????????????????????????
        Resp<OrderEntity> orderEntityResp = null;
        try {
            submitVO.setUserId(userInfo.getId());
            orderEntityResp = this.omsClient.saveOrder(submitVO);
        } catch (Exception e) {
            e.printStackTrace();
            // ???????????????wms????????????????????????
            this.amqpTemplate.convertAndSend("GMALL-ORDER-EXCHANGE", "stock.unlock", orderToken);
            throw new OrderException("???????????????????????????????????????");
        }

        // 5. ??????????????? ?????????????????????????????????
        Map<String, Object> map = new HashMap<>();
        map.put("userId", userInfo.getId());
        List<Long> skuIds = items.stream().map(OrderItemVO::getSkuId).collect(Collectors.toList());
        map.put("skuIds", skuIds);
        this.amqpTemplate.convertAndSend("GMALL-ORDER-EXCHANGE", "cart.delete", map);

        if (orderEntityResp != null) {
            return orderEntityResp.getData();
        }

        return null;
    }

    public static void main(String[] args) {
        ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(1);
        scheduledExecutorService.scheduleAtFixedRate(() -> {
            System.out.println("????????????????????????");
        }, 10l, 20l, TimeUnit.SECONDS);
    }

}
