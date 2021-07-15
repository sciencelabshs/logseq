(ns frontend.extensions.pdf.highlights
  (:require [rum.core :as rum]
            [promesa.core :as p]
            [cljs-bean.core :as bean]
            [frontend.extensions.pdf.utils :as pdf-utils]))

(defonce ACTIVE_FILE "https://phx-nine.vercel.app/clojure-hopl-iv-final.pdf")

(defn dd [& args]
  (apply js/console.debug args))

(rum/defc pdf-highlights
  [^js el ^js viewer initial-hls loaded-pages]

  (let [[sel-state, set-sel-state!] (rum/use-state {:range nil :collapsed nil :point nil})
        [highlights, set-highlights!] (rum/use-state initial-hls)]

    ;; selection events
    (rum/use-effect!
      (fn []
        (let [fn-selection-ok
              (fn [^js/MouseEvent e]
                (let [^js/Selection selection (js/document.getSelection)
                      ^js/Range sel-range (.getRangeAt selection 0)]

                  (cond
                    (.-isCollapsed selection)
                    (set-sel-state! {:collapsed true})

                    (and sel-range (.contains el (.-commonAncestorContainer sel-range)))
                    (set-sel-state! {:collapsed false :range sel-range :point {:x (.-clientX e) :y (.-clientY e)}}))))

              fn-selection
              (fn []
                (let [*dirty (volatile! false)
                      fn-dirty #(vreset! *dirty true)]

                  (js/document.addEventListener "selectionchange" fn-dirty)
                  (js/document.addEventListener "mouseup"
                                                (fn [^js e]
                                                  (and @*dirty (fn-selection-ok e))
                                                  (js/document.removeEventListener "selectionchange" fn-dirty))
                                                #js {:once true})))]

          ;;(doto (.-eventBus viewer))

          (doto el
            (.addEventListener "mousedown" fn-selection))

          ;; destroy
          #(do
             ;;(doto (.-eventBus viewer))

             (doto el
               (.removeEventListener "mousedown" fn-selection)))))

      [viewer])

    ;; selection context menu
    (rum/use-effect!
      (fn []
        (when-let [^js sel-range (and (not (:collapsed sel-state)) (:range sel-state))]
          (when-let [page-info (pdf-utils/get-page-from-range sel-range)]
            (when-let [sel-rects (pdf-utils/get-range-rects<-page-cnt sel-range (:page-el page-info))]
              (let [page (int (:page-number page-info))
                    ^js point (:point sel-state)
                    ^js bounding (pdf-utils/get-bounding-rect sel-rects)
                    vw-pos {:bounding bounding :rects sel-rects :page page}
                    sc-pos (pdf-utils/vw-to-scaled-pos viewer vw-pos)]

                ;; TODO: debug
                (js/console.debug "[VW x SC] ====>" vw-pos sc-pos)
                (js/console.debug "[Range] ====> [" page-info "]" (.toString sel-range) point)
                (js/console.debug "[Rects] ====>" sel-rects " [Bounding] ====>" bounding)

                ;; show context menu
                (set-highlights!
                  (conj highlights {:id         (pdf-utils/gen-id)
                                    :page       page
                                    :position   sc-pos
                                    :content    {:text (.toString sel-range)}
                                    :properties {}}))

                )))))

      [(:range sel-state)])

    ;; render hls
    (rum/use-effect!
      (fn []
        (js/console.debug "[rebuild highlights] " (count highlights))

        (when-let [grouped-hls (and (seq highlights) (group-by :page highlights))]

          (dd "[hls]" grouped-hls))

        ;; destroy
        #())
      [loaded-pages highlights])


    [:div.extensions__pdf-highlights
     [:pre
      (js/JSON.stringify (bean/->js highlights) nil 2)]]))

(rum/defc pdf-viewer
  [url initial-hls ^js pdf-document]

  (js/console.debug "==== render pdf-viewer ====")

  (let [*el-ref (rum/create-ref)
        [state, set-state!] (rum/use-state {:viewer nil :bus nil :link nil :el nil})
        [ano-state, set-ano-state!] (rum/use-state {:loaded-pages []})
        [hls-state, set-hls-state!] (rum/use-state {:dirties 0})]

    ;; instant pdfjs viewer
    (rum/use-effect!
      (fn [] (let [^js event-bus (js/pdfjsViewer.EventBus.)
                   ^js link-service (js/pdfjsViewer.PDFLinkService. #js {:eventBus event-bus :externalLinkTarget 2})
                   ^js el (rum/deref *el-ref)
                   ^js viewer (js/pdfjsViewer.PDFViewer.
                                #js {:container            el
                                     :eventBus             event-bus
                                     :linkService          link-service
                                     :enhanceTextSelection true
                                     :removePageBorders    true})]
               (. link-service setDocument pdf-document)
               (. link-service setViewer viewer)

               ;; TODO: debug
               (set! (. js/window -lsPdfViewer) viewer)

               (p/then (. viewer setDocument pdf-document)
                       #(set-state! {:viewer viewer :bus event-bus :link link-service :el el})))

        ;;TODO: destroy
        #())
      [])

    ;; highlights & annotations
    (rum/use-effect!
      (fn []
        (js/console.debug "[rebuild loaded pages] " (:loaded-pages ano-state))
        ;;(set-hls-state! (update-in hls-state [:dirties] inc))
        ;; destroy
        #())
      [(:loaded-pages ano-state)])

    ;; interaction events
    (rum/use-effect!
      (fn []
        (js/console.debug "[rebuild interaction events]" (:viewer state))

        (when-let [^js viewer (:viewer state)]
          (let [^js el (rum/deref *el-ref)

                fn-textlayer-ready
                (fn [^js p]
                  (js/console.debug "text layer ready" p)
                  (set-ano-state! {:loaded-pages (conj (:loaded-pages ano-state) (.-pageNumber p))}))

                fn-page-ready
                (fn []
                  (set! (. viewer -currentScaleValue) "auto"))]

            (doto (.-eventBus viewer)
              (.on "pagesinit" fn-page-ready)
              (.on "textlayerrendered" fn-textlayer-ready))

            #(do
               (doto (.-eventBus viewer)
                 (.off "pagesinit" fn-page-ready)
                 (.off "textlayerrendered" fn-textlayer-ready))))))

      [(:viewer state)
       (:loaded-pages ano-state)])

    [:div.extensions__pdf-viewer-cnt
     [:div.extensions__pdf-viewer {:ref *el-ref}
      [:div.pdfViewer "viewer pdf"]]

     (if (:viewer state)
       (pdf-highlights
         (:el state) (:viewer state)
         initial-hls
         (:loaded-pages ano-state)))]))

(rum/defc pdf-loader
  [url]
  (let [*doc-ref (rum/use-ref nil)
        [state set-state!] (rum/use-state {:error nil :pdf-document nil :status nil})]

    ;; load
    (rum/use-effect!
      (fn []
        (let [get-doc$ (fn [^js opts] (.-promise (js/pdfjsLib.getDocument opts)))
              own-doc (rum/deref *doc-ref)
              opts {:url           url
                    :ownerDocument js/document
                    ;;:cMapUrl       "./js/pdfjs/cmaps/"
                    :cMapUrl       "https://cdn.jsdelivr.net/npm/pdfjs-dist@2.8.335/cmaps/"
                    :cMapPacked    true}]

          (p/finally
            (p/catch (p/then
                       (do
                         (set-state! {:status :loading})
                         (get-doc$ (clj->js opts)))
                       #(do (js/console.log "+++" %)
                            (set-state! {:pdf-document %})))
                     #(set-state! {:error %}))
            #(set-state! {:status :completed}))

          #()))
      [url])

    [:div.extensions__pdf-loader {:ref *doc-ref}
     (if (= (:status state) :loading)
       [:h1 "Downloading PDF #" url]
       (pdf-viewer url [] (:pdf-document state)))
     [:h3 (str (:error state))]]))

(rum/defc container
  []
  (let [[prepared set-prepared!] (rum/use-state false)]

    ;; load assets
    (rum/use-effect!
      (fn []
        (p/then
          (pdf-utils/load-base-assets$)
          (fn [] (set-prepared! true))))
      [])

    [:div.extensions__pdf-container.flex
     (if prepared
       (pdf-loader ACTIVE_FILE))]))

(rum/defc playground
  []
  [:div.extensions__pdf-playground
   (container)])