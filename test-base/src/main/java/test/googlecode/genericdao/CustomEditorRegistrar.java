package test.googlecode.genericdao;

import org.springframework.beans.PropertyEditorRegistrar;
import org.springframework.beans.PropertyEditorRegistry;
import org.springframework.beans.propertyeditors.CustomDateEditor;

import java.beans.PropertyEditor;
import java.text.SimpleDateFormat;
import java.util.Date;

public class CustomEditorRegistrar implements PropertyEditorRegistrar {
    Class<?> t;
    PropertyEditor propertyEditor;

    public CustomEditorRegistrar(Class<?> t, PropertyEditor propertyEditor) {
        this.t = t;
        this.propertyEditor = propertyEditor;
    }

    @Override
    public void registerCustomEditors(PropertyEditorRegistry registry) {
        registry.registerCustomEditor(t, propertyEditor);
    }

}