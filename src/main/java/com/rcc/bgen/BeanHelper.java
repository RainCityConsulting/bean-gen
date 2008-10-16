package com.rcc.bgen;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
        
public class BeanHelper {
    private static Set primitives = new HashSet(Arrays.asList(new String[] {
            "int", "long", "double", "float", "char", "boolean", "byte"}));

    public String upcaseFirstChar(String s) {
        if (s == null || s.length() == 0) { return ""; }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }   

    public String listToDelimitedString(List list) {
        StringBuilder buf = new StringBuilder();
        for (Object o : list) {
            if (buf.length() > 0) { buf.append(", "); }
            buf.append(o.toString());
        }
        return buf.toString();
    }
        
    public boolean isPrimitive(String type) {
        return primitives.contains(type);
    }

    public String toSqlName(String name) {
        if (name == null || name.length() == 0) { return ""; }
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            if (Character.isUpperCase(name.charAt(i)) && i != 0) {
                buf.append('_');
            }
            buf.append(Character.toLowerCase(name.charAt(i)));
        }
        return buf.toString();
    }
}
