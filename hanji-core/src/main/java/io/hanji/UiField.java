package io.hanji;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects a page element into a controller field (the HTML counterpart of JavaFX's {@code @FXML}).
 * <p>
 * Supported field types: {@link Element}, any component with an {@code (Element)} constructor
 * (e.g. {@code io.hanji.ui.TextField}), and {@link Window} (injects the owning window).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface UiField {

    /** Element id. Defaults to the field name. */
    String value() default "";
}
