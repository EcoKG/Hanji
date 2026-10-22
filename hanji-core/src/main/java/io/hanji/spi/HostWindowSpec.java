package io.hanji.spi;

import java.net.URI;

/**
 * What a host needs to open a native window.
 *
 * @param url    page URL (served on 127.0.0.1)
 * @param title  initial title (the page's document.title takes over once loaded)
 * @param width  width in CSS pixels
 * @param height height in CSS pixels
 * @param x      screen x position or {@code null} for the platform default
 * @param y      screen y position or {@code null} for the platform default
 */
public record HostWindowSpec(URI url, String title, int width, int height, Integer x, Integer y) {
}
