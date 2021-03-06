(ns async-ui.swing.binding
  (:require [clojure.core.async :refer [put!]]
            [async-ui.core :refer [make-event]])
  (:import [javax.swing JButton JComponent JFrame JLabel JList JPanel JScrollPane JTable JTextField]
           [javax.swing.text JTextComponent]
           [java.awt Container]
           [java.awt.event ActionListener WindowListener]
           [javax.swing.event DocumentListener ListSelectionListener]))


; ------------------------------------------------------------------------------
;; Create setter functions to uniformly update properties in visual components


(defn- common-setter-fns
  [vc]
  {:enabled #(.setEnabled vc (if (nil? %) false %))
   :visible #(.setVisible vc (if (nil? %) false %))})


(defn- set-selection!
  [vc index]
  (let [m (.getSelectionModel vc)
        l (.getClientProperty vc :listener)]
    (doto m
      (.removeListSelectionListener l)
      (.setSelectionInterval index index)
      (.addListSelectionListener l))))


(defmulti setter-fns
  "Returns a map from keyword to 1-arg function for the given visual component.
  Each function is used to update a property of the visual component with the 
  given formatted value."
  class)


(defmethod setter-fns :default
  [vc]
  {})


(defmethod setter-fns JButton
  [vc]
  (assoc (common-setter-fns vc)
    :text #(.setText vc %)))


(defmethod setter-fns JFrame
  [vc]
  (assoc (common-setter-fns vc)
    :title #(.setTitle vc %)))


(defmethod setter-fns JLabel
  [vc]
  (assoc (common-setter-fns vc)
    :text #(.setText vc %)))


(defmethod setter-fns JList
  [vc]
  (assoc (common-setter-fns vc)
    :selection #(set-selection! vc (or (first %) 0))
    :items     #(do (.putClientProperty vc :data %)
                    (let [m (.getModel vc)]
                      (.fireContentsChanged m m 0 (count %))))))


(defmethod setter-fns JPanel
  [vc]
  (common-setter-fns vc))


(defmethod setter-fns JTable
  [vc]
  (assoc (common-setter-fns vc)
    :selection #(set-selection! vc (or (first %) 0))
    :items     #(do (.putClientProperty vc :data %)
                    (.repaint vc))))


(defn- set-text!
  "Silently sets the text property of a text component,
  if the textfield does not have the focus."
  [vc text]
  (if-not (.hasFocus vc)
    (let [doc (-> vc .getDocument)
          l (.getClientProperty vc :listener)
          p (.getCaretPosition vc)]
      (.removeDocumentListener doc l)
      (.setText vc text)
      (.addDocumentListener doc l)
      (.setCaretPosition vc (min (count text) p)))))


(defmethod setter-fns JTextComponent
  [vc]
  (assoc (common-setter-fns vc)
    :editable #(.setEditable vc %)
    :text (partial set-text! vc)))



; ------------------------------------------------------------------------------
;; Connect callbacks that write to the events channel of the view


(defn- bind-selection-listener!
  [vc events-chan]
  (let [sel-model (.getSelectionModel vc)
        l         (reify ListSelectionListener
                    (valueChanged [_ evt]
                      (when-not (.getValueIsAdjusting evt)
                        (let [sel (vec (range (.getMinSelectionIndex sel-model)
                                                    (inc (.getMaxSelectionIndex sel-model))))
                              sel (if (= [-1] sel) [] sel)]
                          (put! events-chan (make-event (.getName vc) :selection :update sel))))))]
    (.addListSelectionListener sel-model l)
    (.putClientProperty vc :listener l)))


(defmulti bind!
  "Binds listeners to all components of the visual component tree vc.
  Each listener puts an event onto the channel."
  (fn [vc events-chan]
    (class vc)))


(defmethod bind! :default
  [vc events-chan]
  nil)


(defmethod bind! JButton
  [vc events-chan]
  (.addActionListener vc (reify ActionListener
                           (actionPerformed [_ _]
                             (put! events-chan (make-event (.getName vc) :action))))))


(defmethod bind! JFrame
  [vc events-chan]
  (-> vc (.addWindowListener
          (reify WindowListener
            (windowOpened [_ _])
            (windowClosing [_ _]
              (put! events-chan (make-event (.getName vc) :close)))
            (windowActivated [_ _])
            (windowDeactivated [_ _])
            (windowClosed [_ _]))))
  (bind! (.getContentPane vc) events-chan))


(defmethod bind! JList
  [vc events-chan]
  (bind-selection-listener! vc events-chan))


(defmethod bind! JPanel
  [vc events-chan]
  (doseq [child-vc (.getComponents vc)]
    (bind! child-vc events-chan)))


(defn- text-from-event
  [evt]
  (let [doc (.getDocument evt)]
    (.getText doc 0 (.getLength doc))))


(defmethod bind! JScrollPane
  [vc events-chan]
  (bind! (-> vc .getViewport .getView) events-chan))


(defmethod bind! JTable
  [vc events-chan]
  (bind-selection-listener! vc events-chan))


(defmethod bind! JTextComponent
  [vc events-chan]
  (let [l (reify DocumentListener
            (insertUpdate [_ evt]
              (put! events-chan (make-event (.getName vc) :text :update (text-from-event evt))))
            (removeUpdate [_ evt]
              (put! events-chan (make-event (.getName vc) :text :update (text-from-event evt))))
            (changedUpdate [_ evt]
              (put! events-chan (make-event (.getName vc) :text :update (text-from-event evt)))))]
    (-> vc
        .getDocument
        (.addDocumentListener l))
    (.putClientProperty vc :listener l)))

