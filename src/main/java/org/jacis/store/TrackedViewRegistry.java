/*
 * Copyright (c) 2017. Jan Wiemer
 */

package org.jacis.store;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import org.jacis.container.JacisContainer;
import org.jacis.container.JacisTransactionHandle;
import org.jacis.exception.JacisTrackedViewModificationException;
import org.jacis.plugin.JacisModificationListener;
import org.jacis.plugin.JacisTransactionListener;
import org.jacis.plugin.JacisTransactionListenerAdapter;
import org.jacis.trackedviews.TrackedView;
import org.jacis.trackedviews.TrackedViewClustered;

/**
 * Registry where tracked views can be registered for an object store.
 *
 * @param <K> Key type of the store entry
 * @param <TV> Value type of the store entry
 * @author Jan Wiemer
 */
@SuppressWarnings("unused")
public class TrackedViewRegistry<K, TV> implements JacisModificationListener<K, TV> {

  /** Reference to the JACIS store the tracked view registry belongs to */
  private final JacisStoreImpl<K, TV, ?> store;
  /** Map assigning the tracked views maintained by this registry to the view classes */
  private final Map<String, TrackedView<TV>> viewMap = new HashMap<>();

  TrackedViewRegistry(JacisStoreImpl<K, TV, ?> store, boolean checkConsistencyAfterCommit) {
    this.store = store;
    if (checkConsistencyAfterCommit) {
      JacisTransactionListener txListener = new JacisTransactionListenerAdapter() {

        @Override
        public void afterCommit(JacisContainer container, JacisTransactionHandle tx) {
          checkTrackedViewsAfterCommit();
        }

      };
      store.getContainer().registerTransactionListener(txListener);
    }
  }

  @Override
  public void onModification(K key, TV oldValue, TV newValue, JacisTransactionHandle tx) {
    JacisTrackedViewModificationException toThrow = null;
    for (TrackedView<TV> view : viewMap.values()) {
      try {
        view.trackModification(oldValue, newValue);
      } catch (Exception e) {
        JacisTrackedViewModificationException trackModificationException = new JacisTrackedViewModificationException(store, view, tx, key, oldValue, newValue, e);
        if (toThrow == null) {
          toThrow = trackModificationException;
        } else {
          toThrow.addSuppressed(trackModificationException);
        }
      }
    }
    if (toThrow != null) {
      throw toThrow;
    }
  }

  @SuppressWarnings("unchecked")
  private <VT extends TrackedView<TV>> VT getAndCloneView(String viewName) {
    VT view = (VT) viewMap.get(viewName);
    if (view == null) {
      throw new IllegalArgumentException("No tracked view with name " + viewName + " registered! All registered views: " + viewMap.keySet());
    }
    return (VT) view.clone();
  }

  public <VT extends TrackedView<TV>> void reinitializeView(Class<VT> viewType) {
    store.executeAtomic(() -> initTrackedView(getView(viewType)));
  }

  private void initTrackedView(TrackedView<TV> view) {
    view.clear();
    for (TV val : store.getAllReadOnly(null)) {
      view.trackModification(null, val);
    }
  }

  private void checkTrackedViewsAfterCommit() {
    List<TV> values = store.getAllReadOnly(null);
    for (TrackedView<TV> view : viewMap.values()) {
      view.checkView(values);
    }
  }

  void clearViews() {
    viewMap.values().forEach(TrackedView::clear);
  }

  public Collection<TrackedView<TV>> getAllViews() {
    return viewMap.values();
  }

  public Set<String> getAllViewNames() {
    return viewMap.keySet();
  }

  public void registerTrackedView(String viewName, TrackedView<TV> view) {
    if (!store.getObjectTypeSpec().isTrackOriginalValueEnabled()) {
      throw new IllegalStateException("Registering tracked views is only possible if the store is keeping track of the original values of a transactional view.");
    }
    store.executeAtomic(() -> initTrackedView(view));
    viewMap.put(viewName, view);
  }

  public boolean containsView(String viewName) {
    return viewMap.containsKey(viewName);
  }

  public <VT extends TrackedView<TV>> VT getView(String viewName) {
    JacisStoreTxView<K, TV, ?> internelTxView = store.getTxView();
    VT view;
    if (internelTxView != null) {
      Supplier<VT> viewSupplier = () -> store.computeAtomic(() -> getAndCloneView(viewName));
      view = internelTxView.getTrackedView(viewName, viewSupplier);
    } else { // view is created outside a transaction -> we can not and do not need to track any modification in any transaction
      view = store.computeAtomic(() -> getAndCloneView(viewName));
    }
    return view;
  }

  @SuppressWarnings("unchecked")
  public <VT extends TrackedView<TV>> VT getLifeView(String viewName) {
    return store.computeAtomic(() -> (VT) viewMap.get(viewName));
  }

  @SuppressWarnings("unchecked")
  public <SVK> Collection<SVK> getSubViewKeys(String viewName) {
    return store.computeAtomic(() -> {
      TrackedView<TV> view = viewMap.get(viewName);
      if (view == null) {
        throw new IllegalArgumentException("No tracked view with name " + viewName + " registered! All registered views: " + viewMap.keySet());
      } else if (!TrackedViewClustered.class.isInstance(view)) {
        throw new IllegalArgumentException("The view registered for the name " + viewName + " is no instance of " + TrackedViewClustered.class + "! view: " + view);
      }
      TrackedViewClustered<TV, SVK, ? extends TrackedView<TV>> clusteredView = (TrackedViewClustered<TV, SVK, ? extends TrackedView<TV>>) view;
      return clusteredView.getSubViewKeys();
    });
  }

  @SuppressWarnings("unchecked")
  public <VT extends TrackedView<TV>, VK> VT getSubView(String viewName, VK subviewKey) {
    VT subViewClone = store.computeAtomic(() -> {
      TrackedView<TV> view = viewMap.get(viewName);
      if (view == null) {
        throw new IllegalArgumentException("No tracked view with name " + viewName + " registered! All registered views: " + viewMap.keySet());
      } else if (!TrackedViewClustered.class.isInstance(view)) {
        throw new IllegalArgumentException("The view registered for the name " + viewName + " is no instance of " + TrackedViewClustered.class + "! view: " + view);
      }
      TrackedViewClustered<TV, VK, TrackedView<TV>> clusteredView = (TrackedViewClustered<TV, VK, TrackedView<TV>>) view;
      TrackedView<TV> subView = Objects.requireNonNull(clusteredView.getSubView(subviewKey), "No sub-view found for key " + subviewKey);
      return (VT) subView.clone();
    });
    JacisStoreTxView<K, TV, ?> txView = store.getTxView();
    if (txView != null) {
      for (StoreEntryTxView<K, TV, ?> entryTxView : txView.getAllEntryTxViews()) {
        subViewClone.trackModification(entryTxView.getOrigValue(), entryTxView.getValue());
      }
    }
    return subViewClone;
  }

  // deprecated view management methods:

  private String getDefaultNameForViewClass(Class<?> viewClass) {
    return "VIEW:" + viewClass.getName();
  }

  public void registerTrackedView(TrackedView<TV> view) {
    registerTrackedView(getDefaultNameForViewClass(view.getClass()), view);
  }

  @SuppressWarnings("unchecked")
  public Set<Class<? extends TrackedView<TV>>> getAllViewClasses() {
    Set<Class<? extends TrackedView<TV>>> res = new HashSet<>();
    viewMap.values().stream().forEach(v -> res.add((Class<? extends TrackedView<TV>>) v.getClass()));
    return res;
  }

  public boolean containsView(Class<? extends TrackedView<TV>> viewType) {
    return getAllViewClasses().contains(viewType);
  }

  public <VT extends TrackedView<TV>> VT getView(Class<VT> viewType) {
    return getView(getDefaultNameForViewClass(viewType));
  }

  public <VT extends TrackedView<TV>> VT getLifeView(Class<VT> viewType) {
    return getLifeView(getDefaultNameForViewClass(viewType));
  }

  public <SVK> Collection<SVK> getSubViewKeys(Class<? extends TrackedViewClustered<TV, SVK, ? extends TrackedView<TV>>> viewType) {
    return getSubViewKeys(getDefaultNameForViewClass(viewType));
  }

  public <VT extends TrackedView<TV>, VK> VT getSubView(Class<? extends TrackedViewClustered<TV, VK, VT>> viewType, VK subviewKey) {
    return getSubView(getDefaultNameForViewClass(viewType), subviewKey);
  }

}
