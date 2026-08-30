package com.cafeservices.printgateway;

/**
 * What a print sent to a given address is for.
 *
 * Loyverse fixes the printer port at 9100 and identifies a printer only by its
 * address, so several roles are separated by address instead. Every address in
 * 127.0.0.0/8 is loopback, which means as many printers as we like can be
 * configured on this one device without touching the network.
 */
public enum PrinterRole {

    /** The customer's sale receipt, printed as it always was. */
    RECEIPT("127.0.0.1", "Receipt"),

    /** The WiFi item only: this is what triggers issuing a voucher. */
    VOUCHER("127.0.0.2", "Voucher"),

    UNKNOWN("", "Unrecognised");

    public final String address;
    public final String label;

    PrinterRole(String address, String label) {
        this.address = address;
        this.label = label;
    }

    public static PrinterRole forAddress(String address) {
        if (address == null) {
            return UNKNOWN;
        }
        for (PrinterRole role : values()) {
            if (role != UNKNOWN && role.address.equals(address)) {
                return role;
            }
        }
        // Anything else on loopback is treated as the receipt printer, so a
        // single-printer setup still behaves sensibly.
        return address.startsWith("127.") ? RECEIPT : UNKNOWN;
    }
}
