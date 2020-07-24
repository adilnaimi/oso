package com.osohq.oso;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Function;

public class Polar {
    private Ffi.Polar ffiPolar;
    protected Host host; // visible for tests only
    private Map<String, String> loadQueue; // Map from filename -> file contents

    public Polar() throws Exceptions.OsoException {
        ffiPolar = Ffi.get().polarNew();
        host = new Host(ffiPolar);
        loadQueue = new HashMap<String, String>();
    }

    /**
     * Clear the KB, but maintain all registered classes and calls.
     *
     * @throws Exceptions.OsoException
     */
    public void clear() throws Exceptions.OsoException {
        loadQueue.clear();
        ffiPolar = Ffi.get().polarNew();
    }

    /**
     * Enqueue a polar policy file to be loaded. File contents are loaded into a
     * String and saved here, so changes to the file made after calls to loadFile
     * will not be recognized. If the filename already exists in the load queue,
     * replace it.
     *
     * @param filename
     * @throws Exceptions.PolarFileExtensionError On incorrect file extension.
     * @throws IOException                        If unable to open or read the
     *                                            file.
     */
    public void loadFile(String filename) throws IOException, Exceptions.PolarFileExtensionError {
        Optional<String> ext = Optional.ofNullable(filename).filter(f -> f.contains("."))
                .map(f -> f.substring(filename.lastIndexOf(".") + 1));

        // check file extension
        if (!ext.isPresent() || !ext.get().equals("polar")) {
            throw new Exceptions.PolarFileExtensionError();
        }

        // add file to queue
        loadQueue.put(filename, new String(Files.readAllBytes(Paths.get(filename))));
    }

    /**
     * Load a Polar string into the KB (with filename).
     *
     * @param str      Polar string to be loaded.
     * @param filename Name of the source file.
     * @throws Exceptions.OsoException
     */
    public void loadStr(String str, String filename) throws Exceptions.OsoException {
        ffiPolar.loadStr(str, filename);
        checkInlineQueries();
    }

    /**
     * Load a Polar string into the KB (without filename).
     *
     * @param str Polar string to be loaded.
     * @throws Exceptions.OsoException
     */
    public void loadStr(String str) throws Exceptions.OsoException {
        ffiPolar.loadStr(str, null);
        checkInlineQueries();
    }

    /**
     * Query for a predicate, parsing it first.
     *
     * @param query String string
     * @return Query object (Enumeration of resulting variable bindings).
     */
    public Query query(String query) throws Exceptions.OsoException {
        loadQueuedFiles();
        return new Query(ffiPolar.newQueryFromStr(query), host.clone());
    }

    /**
     * Query for a predicate.
     *
     * @param predicate Predicate name, e.g. "f" for predicate "f(x)".
     * @param args List of predicate arguments.
     * @return Query object (Enumeration of resulting variable bindings).
     * @throws Exceptions.OsoException
     */
    public Query query(String predicate, List<Object> args) throws Exceptions.OsoException {
        loadQueuedFiles();
        Host new_host = host.clone();
        String pred = new_host.toPolarTerm(new Predicate(predicate, args)).toString();
        return new Query(ffiPolar.newQueryFromTerm(pred), new_host);
    }

    /**
     * Start the Polar REPL.
     *
     * @throws Exceptions.OsoException
     */
    public void repl() throws Exceptions.OsoException {
        loadQueuedFiles();
        while (true) {
            Query query = new Query(ffiPolar.newQueryFromRepl(), host);
            if (!query.hasMoreElements()) {
                System.out.println("False");
            } else {
                do {
                    System.out.println(query.nextElement());
                } while (query.hasMoreElements());
            }

        }
    }

    /**
     * Register a Java class with oso.
     *
     * @param cls       Class object to be registered.
     * @param fromPolar lambda function to convert from a
     *                  {@code Map<String, Object>} of parameters to an instance of
     *                  the Java class.
     * @throws Exceptions.DuplicateClassAliasError if class has already been
     *                                             registered.
     */
    public void registerClass(Class cls, Function<Map, Object> fromPolar)
            throws Exceptions.DuplicateClassAliasError, Exceptions.OsoException {
        registerClass(cls, fromPolar, cls.getName());
    }

    /**
     * Register a Java class with oso using an alias.
     *
     * @param cls       Class object to be registered.
     * @param fromPolar lambda function to convert from a
     *                  {@code Map<String, Object>} of parameters to an instance of
     *                  the Java class.
     * @param name     name to register the class under, which is how the class is
     *                 accessed from Polar.
     * @throws Exceptions.DuplicateClassAliasError if a class has already been
     *                                             registered with the given alias.
     */
    public void registerClass(Class cls, Function<Map, Object> fromPolar, String name)
            throws Exceptions.DuplicateClassAliasError, Exceptions.OsoException {
        host.cacheClass(cls, fromPolar, name);
        registerConstant(name, cls);
    }

    /**
     * Registers `value` as a Polar constant variable called `name`.
     *
     * @param name
     * @param value
     * @throws Exceptions.OsoException
     */
    public void registerConstant(String name, Object value) throws Exceptions.OsoException {
        ffiPolar.registerConstant(name, host.toPolarTerm(value).toString());
    }

    /*******************/
    /* PRIVATE METHODS */
    /*******************/

    /**
     * Load all queued files, flushing the {@code loadQueue}
     *
     * @throws Exceptions.OsoException
     */
    private void loadQueuedFiles() throws Exceptions.OsoException {
        for (String fname : loadQueue.keySet()) {
            loadStr(loadQueue.get(fname), fname);
        }
        loadQueue.clear();
    }

    /**
     * Confirm that all queued inline queries succeed.
     *
     * @throws Exceptions.OsoException           On failed query creation.
     * @throws Exceptions.InlineQueryFailedError On inline query failure.
     */
    private void checkInlineQueries() throws Exceptions.OsoException, Exceptions.InlineQueryFailedError {
        Ffi.Query nextQuery = ffiPolar.nextInlineQuery();
        while (nextQuery != null) {
            if (!new Query(nextQuery, host).hasMoreElements()) {
                throw new Exceptions.InlineQueryFailedError();
            }
            nextQuery = ffiPolar.nextInlineQuery();
        }
    }
}