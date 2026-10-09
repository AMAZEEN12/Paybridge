package com.academy.paybridge.transfer.gateway;

public record GatewayResult(GatewayStatus status, String gatewayReference, String message) {
}
