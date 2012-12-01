package uk.me.desert_island.theorbtwo.bridge;

import java.io.PrintStream;
import java.io.IOException;
import java.lang.Class;
import java.util.HashMap;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import java.util.ArrayList;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

public class Core {
    private static HashMap<String, Object> known_objects = new HashMap<String, Object>();
    
    private static Object handle_call(JSONArray incoming, PrintyThing err) 
        throws JSONException, Exception
    {
        //  0      1          2                    3 4      5                                          6     7
        // ["call","31247544","class_call_handler",0,"call","Object::Remote::Java::java::lang::System","can","getProperties"]
        String call_type = incoming.getString(2);
        
        if (call_type.equals("class_call_handler")) {
            // wantarray may be undef/0/1, so call it an Object, not an int or a string.
            //Object wantarray = incoming.get(3);
            String call_type_again = incoming.getString(4);
            if (call_type_again.equals("call")) {
                String perl_class = incoming.getString(5);
                String perl_method = incoming.getString(6);
                
                if (perl_method.equals("can")) {
                    String perl_wanted_method = incoming.getString(7);
                    Class<?> klass = my_find_class(perl_class);
                    if (has_any_methods(klass, perl_wanted_method)) {
                        err.print("Need to return true for " + perl_class + "->can('" + perl_wanted_method +"')\n");
                        return (new CanResult(klass, perl_wanted_method));
                    } else {
                        err.print("Need to return false for " + perl_class + "->can('" + perl_wanted_method+"')\n");
                        return null;
                    }
                    
                } else if (perl_method.equals("new")) {
                    // Right.  This is a constructor, which in Java isn't a method call, but a special unique flower.
                    //  0      1           2                    3 4      5                           6     7
                    // ["call","136826936","class_call_handler",0,"call","android::widget::EditText","new",{"__local_object__":"uk.me.desert_island.theorbtwo.bridge.JavaBridgeService:410a5a10"}]
                    Class<?> klass = my_find_class(perl_class);
                    err.print("Calling 'new' on .. " + klass.getName());
                    ArrayList<Object> args = new ArrayList<Object>();
                    ArrayList<Class> arg_types = new ArrayList<Class>();
                    // magic number 7 = start extracting args at this index
                    convert_json_args_to_java(incoming, 7, arg_types, args, err);
                    Constructor con = my_find_constructor(klass, arg_types.toArray(new Class<?>[0]));
                    return con.newInstance(args.toArray());
                } else {
                    err.print("class call for " + perl_class +"->" + perl_method +"\n");
                }
            } else {
                err.print("Huh?\n");
                err.print("call_type_again: '" + call_type_again +"'\n");
            }
        } else if (known_objects.containsKey(call_type)) {
            // A normal method call.
            Object obj = known_objects.get(call_type);
            String do_what = incoming.getString(4);
            if (do_what.equals("call") && obj instanceof CanResult) {
                // >>> ["call","139214384","class_call_handler",0,"call","uk::me::desert_island::theorbtwo::bridge::AndroidServiceStash","can","get_service"]
                // <<< ["call_free","NULL","139214384","done",{"__remote_code__":"uk.me.desert_island.theorbtwo.bridge.CanResult:410c7850"}]
                // >>> ["call","139097736","uk.me.desert_island.theorbtwo.bridge.CanResult:410c7850","","call"]
                // <<< ["call_free","NULL","139097736","done",{"__remote_object__":"uk.me.desert_island.theorbtwo.bridge.JavaBridgeService:410b83f0"}]

                //  0       1          2                                                         3  4      5                        6                                 7               8
                // ["call","139214400","uk.me.desert_island.theorbtwo.bridge.CanResult:410d8508","","call","android::widget::Toast",{"__remote_object__":"139315920"},"toast content",0]
                // ["call","139175944","uk.me.desert_island.theorbtwo.bridge.CanResult:410d9db8","","call","android::widget::Toast",{"__remote_object__":"uk.me.desert_island.theorbtwo.bridge.JavaBridgeService:410b9d40"},"toast content",0]
                // ["call","139178632","uk.me.desert_island.theorbtwo.bridge.CanResult:41093298","","call","android::widget::Toast",{"__local_object__":"uk.me.desert_island.theorbtwo.bridge.JavaBridgeService:410b0f88"},"toast content",0]


                // A call to a coderef (a CanResult from ->can::on, most likely).
                // Object wantarray = incoming.getString(3);
                CanResult canresult = (CanResult)obj;
                //if (incoming.length() != 5) {
                //    throw(new Exception("calls with arguments not yet supported"));
                //}
                Class method_class = canresult.klass();
                String method_name = canresult.method_name();

                Boolean static_call;
                Object invocant = null;
                ArrayList<Object> args = new ArrayList<Object>();
                ArrayList<Class> arg_types = new ArrayList<Class>();

                if(incoming.length() > 5) {
                    if (incoming.get(5) instanceof String) {
                        // FIXME: how do we tell the difference between a static method call and a call that happens to be on a string?
                        static_call = true;
                        invocant = null;
                    } else {
                        static_call = false;
                        // JSONArray containing JSONObject with key "__local_object__", which contains the id of an object we fetched earlier (probably)
                        invocant = known_objects.get(incoming.getJSONObject(5).get("__local_object__"));
                        if(invocant == null) {
                            err.print("Can't find invocant object from:" + incoming.get(5));
                        }
                    }                
                }

                // magic number 6 = start extracting args at this index
                convert_json_args_to_java(incoming, 6, arg_types, args, err);
                
                Method meth = my_find_method(method_class, method_name, arg_types.toArray(new Class<?>[0]));
                return meth.invoke(invocant, args.toArray());
                
            } else {
                // Really, just a normal method call.
                //  0      1          2                              3   4             5
                // ["call","24740200","java.util.Properties:b4aa453","1","getProperty","java.class.path"]
                // ["call","139255176","android.hardware.Sensor:410d9670","","getMaximumRange"]
                ArrayList<Object> args = new ArrayList<Object>();
                ArrayList<Class> arg_types = new ArrayList<Class>();
                // magic number 5 = start extracting args at this index
                convert_json_args_to_java(incoming, 5, arg_types, args, err);

                Method meth = my_find_method(obj.getClass(), do_what, arg_types.toArray(new Class<?>[0]));
                return meth.invoke(obj, args.toArray());
                
            }
        } else {
            err.print("Huh, unknown call_type " + call_type +"\n");
        }

        return null;        
    }
    
    public static void handle_line(StringBuilder in_line, PrintStream out, PrintyThing err) 
        throws JSONException, Exception
    {
        JSONArray incoming = new JSONArray(in_line.toString());
        String command = incoming.getString(0);
        String future_objid = incoming.getString(1);
        
        Object retval;
        Boolean is_fail;
        
        err.print("command_string = '"+command+"', future_objid = '"+future_objid+"'\n");
        
        if (command.equals("call")) {
            try {
                retval = handle_call(incoming, err);
                is_fail = false;
            } catch (Exception e) {
                is_fail = true;
                // Good god, but this is ugly.
                ByteArrayOutputStream baos = new ByteArrayOutputStream();

                // FIXME: Framework (O::R) should do this?
                e.printStackTrace();
                e.printStackTrace(new PrintStream(baos));
                retval = e.toString() + " message " + e.getMessage() + " stack trace: " + baos.toString();
            }
        } else {
            err.print("Huh?\n");
            err.print("command: "+ command + "\n");
            throw(new Exception("Protocol error?"));
        }

        JSONArray json_out = new JSONArray();
        json_out.put("call_free");
        json_out.put("NULL");
        json_out.put(future_objid);
        if (is_fail) {
            json_out.put("fail");
        } else {
            json_out.put("done");
        }

        if (retval == null) {
            err.print("Return is null\n");
        } else {
            err.print("Return (toString): " + retval + "\n");
            err.print("Return (class):    " + retval.getClass().toString() + "\n");
        }

        if (retval == null) {
            json_out.put(JSONObject.NULL);
        } else if (retval instanceof CanResult) {
            JSONObject return_json = new JSONObject();
            String ret_objid = obj_ident(retval);
            known_objects.put(ret_objid, retval);
            return_json.put("__remote_code__", ret_objid);
            json_out.put(return_json);
        
        } // For the wrapped basic types, we want the specific JSONObject.put for that basic type.
        else if (retval.getClass() == String.class) {
            json_out.put((String)retval);
        } else if (retval.getClass() == Float.class) {
            json_out.put(((Float)retval).doubleValue());
        } else if (retval.getClass() == Double.class) {
            json_out.put(((Double)retval).doubleValue());
        } else if (retval.getClass() == Integer.class) {
            json_out.put(((Integer)retval).intValue());
        } else {
            // FIXME: Quite possibly we shouldn't return all of these as objects, but rather as plain strings or numbers.
            JSONObject return_json = new JSONObject();
            String ret_objid = obj_ident(retval);
            err.print("Store object: " + ret_objid);
            known_objects.put(ret_objid, retval);
            return_json.put("__remote_object__", ret_objid);
            json_out.put(return_json);
        }
            
        out.println(json_out.toString());
        
    }

    private static void convert_json_args_to_java
        (JSONArray incoming, int start_index, ArrayList<Class> arg_types, ArrayList<Object> args, PrintyThing err) throws JSONException {
        for (int i = start_index; i < incoming.length(); i++) {
            Object json_arg = incoming.get(i);
            err.print("JSON arg: " + json_arg.toString());
            if(json_arg instanceof JSONObject) {
                // FIXME: Doesn't handle __remote_code__ yet.. 
                err.print("Looking for known obj: " + ((JSONObject) json_arg).get("__local_object__"));
                json_arg = known_objects.get(((JSONObject) json_arg).get("__local_object__"));
            }
            // if(known_objects.containsKey((String)json_arg)) {
            //    json_arg = known_objects.get((String)json_arg);
            // }
            args.add(json_arg);
            arg_types.add(json_arg.getClass());
        }
    }

    
    private static Class<?> my_find_class(String perl_name) {
        String java_name;

        if (perl_name.startsWith("Object::Remote::Java::")) {
            java_name = perl_name.substring(22);
        } else {
            java_name = perl_name;
        }
        java_name = java_name.replaceAll("::", ".");
        System.err.printf("perl name %s is java name %s\n", perl_name, java_name);

        try {
            return Class.forName(java_name);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    // Returns true if we have *any* method that might work for this name, when we need a result for ->can.
    private static boolean has_any_methods(Class<?> klass, String name) {
        Method[] meths = klass.getMethods();
        for (Method meth : meths) {
            if (meth.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean compare_method_args(Class<?>[] args, Class<?>[] m_args)
    {
        boolean found=true;
        
        for (int i=0; i<args.length; i++) {
            
            String wanted_name = args[i].getName();
            String got_name = m_args[i].getName();
            
            // Java Language Specification, 3rd edition, 5.3 -- method arguments can have...
            // • an identity conversion (§5.1.1)
            if (args[i].equals(m_args[i])) {
                continue;
            }
            
            // • a widening primitive conversion (§5.1.2)
            // (Not applicable; our arguments will always be boxed types.)
            
            // • a widening reference conversion (§5.1.5)
            if (m_args[i].isAssignableFrom(args[i])) {
                System.err.printf("%s vs %s is OK (isAssignableFrom / a widening reference conversion\n",
                                  wanted_name, got_name
                                  );
                continue;
            }
            
            // • a boxing conversion (§5.1.7) optionally followed by widening reference conversion
            // • an unboxing conversion (§5.1.8) optionally followed by a widening primitive conversion.
            
            // Java Language Specification, 3rd edition, 5.1.8
            if (wanted_name.equals("java.lang.Boolean") && got_name.equals("boolean")) {
                continue;
            }
            
            if (wanted_name.equals("java.lang.Byte") && got_name.equals("byte")) {
                continue;
            }
            
            if (wanted_name.equals("java.lang.Character") && got_name.equals("char")) {
                continue;
            }
            
            if (wanted_name.equals("java.lang.Short") && got_name.equals("short")) {
                continue;
            }
            
            if (wanted_name.equals("java.lang.Integer") && got_name.equals("int")) {
                continue;
            }
            
            if (wanted_name.equals("java.lang.Long") && got_name.equals("long")) {
                continue;
            }
            
            if (wanted_name.equals("java.lang.Float") && got_name.equals("float")) {
                continue;
            }
            
            if (wanted_name.equals("java.lang.Double") && got_name.equals("double")) {
                continue;
            }
            
            if (wanted_name.equals("java.lang.Integer") && got_name.equals("int")) {
                continue;
            }
            System.err.printf("Argument mismatch on wanted_name='%s' vs got_name='%s'\n", wanted_name, got_name);
            found = false;
            break;
        }

        return found;
    }
    
    private static Constructor my_find_constructor(Class<?> klass, Class<?>[] args)
        throws SecurityException, NoSuchMethodException
    {
        System.err.printf("my_find_constructor, class = ", klass.getName(), ", args = ...");
        for (Class<?> arg_k : args) {
            System.err.printf("\n arg: "+arg_k.getName());
        }
        try {
            Constructor c;
            System.err.printf("Trying to find an obvious constructor\n");
            c = klass.getConstructor(args);
            System.err.printf("Still here after getConstructor() call\n");
            return c;
        } catch (NoSuchMethodException e) {
            // Do nothing (just don't return).
        }

        System.err.printf("Trying non-obvious matches\n");
        // We do not have a perfect match; try for a match where the
        // method has primitive types but args has corresponding boxed types.
        for (Constructor c : klass.getConstructors()) {
            Class<?>[] c_args;

            c_args = c.getParameterTypes();

            if (c_args.length != args.length) {
                continue;
            }

            System.err.printf("We have a strong candidate %s\n", c.toString());

            if (compare_method_args(args, c_args)) {
                System.err.printf("We got it: %s\n", c.toString());
                return c;
            }
        }

        throw new NoSuchMethodException();

    }
    
    private static Method my_find_method(Class<?> klass, String name, Class<?>[] args) 
        throws SecurityException, NoSuchMethodException
    {
    
        try {
            Method m;
            System.err.printf("Trying to find an obvious method for name=%s\n", name);
            m = klass.getMethod(name, args);
            System.err.printf("Still here after getMethod() call\n");
            return m;
        } catch (NoSuchMethodException e) {
            // Do nothing (just don't return).
        }

        System.err.printf("Trying non-obvious matches\n");
        // We do not have a perfect match; try for a match where the
        // method has primitive types but args has corresponding boxed types.
        for (Method m : klass.getMethods()) {
            Class<?>[] m_args;

            if (!m.getName().equals(name)) {
                continue;
            }

            m_args = m.getParameterTypes();

            if (m_args.length != args.length) {
                continue;
            }

            System.err.printf("We have a strong canidate %s\n", m.toString());

            if (compare_method_args(args, m_args)) {
                System.err.printf("We got it: %s\n", m.toString());
                return m;
            }
        }

        throw new NoSuchMethodException();
    }



    private static String obj_ident(java.lang.Object obj) {
        StringBuilder ret = new StringBuilder();
        if (obj == null) {
            return "null";
        }
        ret = ret.append(obj.getClass().getName());
        ret = ret.append(":");
        ret = ret.append(Integer.toHexString(System.identityHashCode(obj)));

        return ret.toString();
    }
}
