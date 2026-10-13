package com.academy.paybridge.shared.money;

/** One row of the countries table: a country and the currency its customers hold as their home wallet. */
public record CountryInfo(String code, String name, String currencyCode) {
}