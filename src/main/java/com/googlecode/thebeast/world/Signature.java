package com.googlecode.thebeast.world;

import java.io.Serializable;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * A Signature maintains a set of types, predicates and functions. In
 * particular, it provides a mapping from their String names to the
 * corresponding objects.
 *
 * @author Sebastian Riedel
 * @see Type
 */
public final class Signature implements Serializable {

  /**
   * Serial version id for java serialization.
   */
  private static final long serialVersionUID = 1999L;

  /**
   * The Id to be given to the next possible world to create.
   */
  private int currentWorldId = 0;

  /**
   * The pool of sql tables to reuse.
   */
  private SQLTablePool sqlTablePool;

  /**
   * A map from type names to types. This map contains user types as well as
   * built-in types.
   *
   * @see UserType
   */
  private final LinkedHashMap<String, Type>
    types = new LinkedHashMap<String, Type>();

  /**
   * A mapping from names to user types.
   */
  private final LinkedHashMap<String, UserType>
    userTypes = new LinkedHashMap<String, UserType>();

  /**
   * A mapping from predicate names to predicates.
   */
  private final LinkedHashMap<String, Predicate>
    predicates = new LinkedHashMap<String, Predicate>();

  /**
   * Stores all user predicates.
   */
  private final LinkedHashMap<String, UserPredicate>
    userPredicates = new LinkedHashMap<String, UserPredicate>();

  /**
   * A mapping from names to symbols (types, predicates, constants and
   * functions).
   */
  private final LinkedHashMap<String, Symbol>
    symbols = new LinkedHashMap<String, Symbol>();


  /**
   * The list of listeners of this signature.
   */
  private final ArrayList<SignatureListener>
    listeners = new ArrayList<SignatureListener>();

  /**
   * Connection to database that is used to store ground atoms.
   */
  private Connection connection;

  /**
   * Creates a new signature and opens a connection to the H2 database.
   */
  public Signature() {
    try {
      Class.forName("org.h2.Driver");
      connection = DriverManager.getConnection("jdbc:h2:~/test", "sa", "");
      sqlTablePool = new SQLTablePool(this);
    } catch (ClassNotFoundException e) {
      e.printStackTrace();
    } catch (SQLException e) {
      e.printStackTrace();
    }
  }


  /**
   * Tests whether the signature equals the given one. If not a runtime
   * exception is thrown. This is mainly a convenience method for clients how
   * want to ensure that arguments to their operations have matching types.
   *
   * @param signature the signature to match.
   * @throws SignatureMismatchException when signatures do not match.
   */
  public void match(final Signature signature)
    throws SignatureMismatchException {
    if (!this.equals(signature)) {
      throw new SignatureMismatchException("Signatures do not match",
        this, signature);
    }

  }

  /**
   * Provides a signature-wide database connection to classes of this package.
   *
   * @return the database connection of this signature.
   */
  Connection getConnection() {
    return connection;
  }


  /**
   * Returns the pool of tables that represents relations of this signature.
   *
   * @return an SQLTablePool that manages tables representing relations of this
   *         signature.
   */
  SQLTablePool getSqlTablePool() {
    return sqlTablePool;
  }

  /**
   * Add a symbol to the signature. This method is called by all symbol factor
   * methods in this class and in {@link Type}.
   *
   * @param symbol the symbol we want to register.
   * @throws SymbolAlreadyExistsException if there is a symbol with the same
   *                                      name.
   */
  void registerSymbol(final Symbol symbol) throws SymbolAlreadyExistsException {
    Symbol old = symbols.get(symbol.getName());
    if (old != null) {
      throw new SymbolAlreadyExistsException(old, this);
    }
    symbols.put(symbol.getName(), symbol);
    for (SignatureListener l : listeners) {
      l.symbolAdded(symbol);
    }
  }

  /**
   * Unregistered the symbol from this signature.
   *
   * @param symbol the symbol to be removed from the signature.
   * @throws SymbolNotPartOfSignatureException
   *          if the symbol is not a member of this signature (e.g. it has been
   *          created by a different signature object).
   */
  void unregisterSymbol(final Symbol symbol)
    throws SymbolNotPartOfSignatureException {
    if (!symbol.getSignature().equals(this)) {
      throw new SymbolNotPartOfSignatureException(symbol, this);
    }
    symbols.remove(symbol.getName());
    for (SignatureListener l : listeners) {
      l.symbolRemoved(symbol);
    }

  }

  /**
   * Returns the symbol for the given name.
   *
   * @param name the name of the symbol.
   * @return the symbol with the given name.
   */
  public Symbol getSymbol(final String name) {
    return symbols.get(name);
  }


  /**
   * Adds the given listener to the listeners of this signature. Will be
   * notified of any changes to it.
   *
   * @param signatureListener a listener to signature events.
   */
  public void addSignatureListener(final SignatureListener signatureListener) {
    listeners.add(signatureListener);
  }

  /**
   * Removes the specified listener from the list of listeners this signature
   * maintains.
   *
   * @param signatureListener the listener to remove.
   */
  public void removeSignatureListener(
    final SignatureListener signatureListener) {
    listeners.remove(signatureListener);
  }


  /**
   * Creates a new possible world.
   *
   * @return a new possible world with unique id wrt to this signature.
   */
  public World createWorld() {
    return new World(this, currentWorldId++);
  }

  /**
   * Creates a new UserType with the given name.
   *
   * @param name       the name of the type.
   * @param extendable if the type can create new constants when queried for
   *                   constants with unknown names.
   * @return a UserType with the given name.
   * @throws SymbolAlreadyExistsException if there is a symbol with the same
   *                                      name in the signature.
   */
  public UserType createType(final String name, final boolean extendable)
    throws SymbolAlreadyExistsException {

    UserType type = new UserType(name, extendable, this);
    registerSymbol(type);
    types.put(name, type);
    userTypes.put(name, type);
    for (SignatureListener l : listeners) {
      l.symbolAdded(type);
    }
    return type;
  }


  /**
   * Convenience method to create a type that already contains a set of
   * constants.
   *
   * @param name       the name of the type.
   * @param extendable whether the type should be extendable on the fly.
   * @param constants  a vararg array of constant names.
   * @return a type that contains constants with the provided names.
   */
  public UserType createType(final String name, final boolean extendable,
                             final String... constants) {
    UserType type = createType(name, extendable);
    for (String constant : constants) {
      type.createConstant(constant);
    }
    return type;
  }

  /**
   * Removes a type from the signature.
   *
   * @param type the type to remove from the signature.
   * @throws SymbolNotPartOfSignatureException
   *          if the type is not a member of the signature (e.g. because it was
   *          created by a different signature object).
   */
  public void removeType(final Type type)
    throws SymbolNotPartOfSignatureException {
    unregisterSymbol(type);
    types.remove(type.getName());
  }

  /**
   * Creates a new UserPredicate and stores it in this signature.
   *
   * @param name          the name of the new predicate
   * @param argumentTypes a list with its argument types.
   * @return a UserPredicate with the specified properties.
   * @throws SymbolAlreadyExistsException if there is a symbol with the same
   *                                      name in the signature.
   */
  public UserPredicate createPredicate(final String name,
                                       final List<Type> argumentTypes)
    throws SymbolAlreadyExistsException {

    ArrayList<SQLRepresentableType>
      sqlTypes = new ArrayList<SQLRepresentableType>();
    for (Type type : argumentTypes) {
      match(type.getSignature());
      sqlTypes.add((SQLRepresentableType) type);
    }
    UserPredicate predicate = new UserPredicate(name, sqlTypes, this);
    registerSymbol(predicate);
    predicates.put(name, predicate);
    userPredicates.put(name, predicate);
    return predicate;
  }

  /**
   * Convenience method to create predicates without using a list.
   *
   * @param name          the name of the predicate
   * @param argumentTypes an vararg array of argument types
   * @return a UserPredicate with the specified properties.
   * @throws SymbolAlreadyExistsException if there is a symbol in the signature
   *                                      that already has this name.
   */
  public UserPredicate createPredicate(final String name,
                                       final Type... argumentTypes)
    throws SymbolAlreadyExistsException {
    return createPredicate(name, Arrays.asList(argumentTypes));
  }

  /**
   * Removes a predicate from the signature.
   *
   * @param predicate the predicate to remove from the signature.
   * @throws SymbolNotPartOfSignatureException
   *          if the predicate is not a member of the signature (e.g. because it
   *          was created by a different signature object).
   */
  public void removePredicate(final UserPredicate predicate)
    throws SymbolNotPartOfSignatureException {
    unregisterSymbol(predicate);
    predicates.remove(predicate.getName());
    userPredicates.remove(predicate.getName());
  }


  /**
   * Returns the type corresponding to the given type name. An exception is
   * thrown if there is no such type. If you want to find out whether a type
   * exists use {@link Signature#getTypeNames()} and {@link
   * Set#contains(Object)} instead.
   *
   * @param name the name of the type to return.
   * @return either a built-in type of a {@link UserType}
   * @throws TypeNotInSignatureException if there is no type with the given
   *                                     name.
   */
  public Type getType(final String name) throws TypeNotInSignatureException {
    Type type = types.get(name);
    if (type == null) {
      throw new TypeNotInSignatureException(name, this);
    }
    return type;
  }

  /**
   * Returns the user type corresponding to the given name.
   *
   * @param name the name of the type to return.
   * @return the user type with the given name.
   * @throws TypeNotInSignatureException if there is no type with this name.
   */
  public UserType getUserType(final String name)
    throws TypeNotInSignatureException {
    UserType type = userTypes.get(name);
    if (type == null) {
      throw new TypeNotInSignatureException(name, this);
    }
    return type;
  }

  /**
   * Returns the set of type names this signature maintains.
   *
   * @return an unmodifiable view on the set of type names.
   */
  public Set<String> getTypeNames() {
    return Collections.unmodifiableSet(types.keySet());
  }


  /**
   * Returns the predicate with the given name if available. Returns both user
   * and built-in predicates.
   *
   * @param name the name of the predicate to return.
   * @return a predicate of this signature with the given name.
   * @throws PredicateNotInSignatureException
   *          if there is not predicate with the given name.
   */
  public Predicate getPredicate(final String name)
    throws PredicateNotInSignatureException {
    return predicates.get(name);
  }

  /**
   * Returns the set of predicate names this signature maintains.
   *
   * @return an unmodifiable view on the set of predicate names.
   */
  public Set<String> getPredicateNames() {
    return Collections.unmodifiableSet(predicates.keySet());
  }

  /**
   * Returns the collection user predicates in this signature.
   *
   * @return an unmodifiable view on the set of user predicates.
   */
  public Collection<UserPredicate> getUserPredicates() {
    return Collections.unmodifiableCollection(userPredicates.values());
  }

  /**
   * Returns the set of all types this signature maintains.
   *
   * @return a collection of all types in this signature. Iterating over this
   *         collection maintains the order of type creation.
   */
  public Collection<Type> getTypes() {
    return Collections.unmodifiableCollection(types.values());
  }


}
