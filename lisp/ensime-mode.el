;;; ensime-mode.el --- ENSIME support -*- lexical-binding: t -*-

;; Copyright (C) 2021 Sam Halliday
;; License: GPL 3 or any later version

;; Homepage: https://ensime.github.io
;; Keywords: languages
;; Version: 0.0.1
;; Package-Requires: ((emacs "27.1") (popup "0.5.3") (company "0.9.12"))

(require 'arc-mode)
(require 'company)
(require 'thingatpt)

;; Popups are not supported in stock Emacs so an extension is necessary:
;; https://emacs.stackexchange.com/questions/53373
;;
;; `x-show-tip' looks horrible and has no way to control when the popup closes.
;; `x-popup-menu' looks horrible and is incredibly complicated.
(require 'popup)

;;;###autoload
(defun ensime-type-at-point ()
  "Show the inferred type at point in the minibuffer."
  (interactive)
  (message (car (ensime--command-at-point "type"))))

;;;###autoload
(defun ensime-symbol-at-point ()
  "Show the inferred type at point in the minibuffer."
  (interactive)
  (message (car (ensime--command-at-point "symbol"))))

;;;###autoload
(defun ensime-import-symbol-at-point ()
  "Fully qualify the symbol at point and add to the imports at the top of the file."
  (interactive)
  (when-let
      ((sym (save-excursion
              ;; if the user types something like "Files." they will likely
              ;; want to import at the moment they realise that Files is not
              ;; imported, hence why we skip past dots.
              (when (looking-back "." (- (point) 1))
                (goto-char (- (point) 1)))
              (symbol-at-point)))
       (hits (ensime--command (list "search" (symbol-name sym))))
       (hit (if (null (cdr hits)) (car hits) (popup-menu* hits))))
    (ensime--add-import hit)))

;;;###autoload
(defun ensime-jump-to-definition ()
  "Retrieve the source definition(s) for the symbol at point and go there.
May ask the user to disambiguate."
  (interactive)
  (when-let* ((srcs (ensime--command-at-point "source"))
              (prefix (ensime--common-prefix srcs))
              (cleaned (mapcar
                        (lambda (e) (string-remove-prefix prefix e))
                        srcs))
              (choice (concat prefix
                              (if (null (cdr cleaned)) (car cleaned) (popup-menu* cleaned))))
              (line (string-to-number (string-trim-left choice (rx (* any) ":"))))
              (path (string-trim-right choice (rx ":" (+ digit))))
              (file (string-trim-right path (rx "!/" (* any)))))
    (if (string-empty-p path)
        (progn
          (goto-char (point-min))
          (forward-line (- line 1)))
      (when (file-exists-p file)
        (find-file file)
        (unless (equal file path)
          (let ((entry (string-trim-left path (rx (* any) "!/")))
                (archive (current-buffer)))
            (goto-char (point-min))
            (re-search-forward (rx-to-string `(: (* any) space ,entry)))
            (archive-extract)
            (kill-buffer archive)
            (read-only-mode 1)))
        (progn
          (goto-char (point-min))
          (forward-line (- line 1)))))))

(defun ensime--common-prefix (strings)
  "Finds the string that is the longest common prefix of STRINGS."
  ;; (ensime--common-prefix nil)
  ;; (ensime--common-prefix '("foo"))
  ;; (ensime--common-prefix '("foo" "bar"))
  ;; (ensime--common-prefix '("foo" "fob"))
  ;; (ensime--common-prefix '("foo" "fob" "fumb"))
  (cond
   ((null strings) nil)
   ((null (cdr strings)) (car strings))
   ((null (cddr strings))
    (let* ((left (string-to-list (car strings)))
           (right (string-to-list (cadr strings)))
           (prefix nil))
      (while (and
              (not (null left))
              (equal (car left) (car right)))
        (pop left)
        (push (pop right) prefix))
      (concat (reverse prefix))))
   (t (let ((prefix (car strings)))
        (dolist (s (cdr strings) prefix)
          (setq prefix (ensime--common-prefix (list prefix s))))))))

(defun ensime--add-import (fqn)
  (save-excursion
    (goto-char (point-min))
    (if (re-search-forward (rx line-start "import" word-end) nil t)
        (progn
          (forward-line 0)
          (while
              (and
               (re-search-forward (rx line-start "import" word-end (group (+ not-newline)) line-end) nil t)
               (string-lessp (string-trim (match-string 1)) fqn)))
          (forward-line 0))
      (when (re-search-forward (rx line-start "package" word-end) nil t)
        (forward-line 1))
      (insert "\n"))
    (insert "import " fqn "\n")
    (message "Imported `%s'" fqn)))

;;;###autoload
(defun ensime-company (command &optional arg &rest ignored)
  "`company-mode' backend for ENSIME."
  (interactive (list 'interactive))

  (when (symbol-value 'ensime-mode)
    (pcase command
      ('prefix
       ;; entry 4 is comment
       (when (not (nth 4 (syntax-ppss)))
         (cond
          ((= 46 (char-before)) '("" . t))
          ((looking-back (rx "." (+ word)) (- (point) 10)) (company-grab-symbol-cons (rx ".") 1))
          ('t nil))))

      ('candidates
       (when (= 0 (length arg))
         (seq-map
          #'ensime--candidate
          (all-completions arg (ensime--command-at-point "complete")))))

      ('annotation
       (get-text-property 0 'meta arg))

      ('post-completion
       (when (equal "apply" arg)
         (delete-char -6))

       (if (string-match-p (rx string-start (+ (not (syntax word))) string-end) arg)
           (progn
             ;; symbolic completion
             (delete-char (- 0 (length arg) 1))
             (insert " " arg " "))
         (let ((paramss (ensime--signature (get-text-property 0 'meta arg))))
           ;; the company templating engine doesn't support being called multiple
           ;; times so we only expand the first paramss
           (when (car paramss)
             (insert (car paramss))
             (company-template-c-like-templatify (car paramss))))))

      ('sorted t))))

;; converts a scala member signature into a list of C style arguments
(defun ensime--signature (sig)
  ;; (ensime--signature "[blah](foo: String, bar: Map[String, String])(baz: String)(implicit ctx: Context)")
  (with-temp-buffer
    (insert sig)
    (goto-char (point-min))
    (while (re-search-forward (rx (| "[" "(" ":")) nil t)
      (let ((c (char-before)))
        (forward-char -1)
        (pcase c
          ('?\[
           (kill-sexp))
          ('?\(
           (if (looking-at-p (rx "(implicit" word-end))
               (kill-line)
             (forward-sexp)
             (insert "\n")))
          ('?\:
           (kill-line)))))
    (split-string
     (buffer-substring-no-properties (point-min) (point-max))
     "\n" t)))

;; a cache of the string HASH value that is associated to this file. This should
;; be updated every time there is a call in this buffer to the ensime launcher.
;; If the value changes from the pre-cached version, all cached values in all
;; buffers should be updated. Consider the case where the user rebuilds a
;; project using a different scala version, this will catch that scenario.
;;
;; the value 'none is used if there is no known ensime launcher for this file.
(defvar-local ensime--hash nil)
(defun ensime--hash (force)
  (setq
   ensime--hash
   (if (or force (null ensime--hash))
       (let ((launcher (ensime--launcher)))
         (if (null launcher)
             'none
           (with-temp-buffer
             (insert-file-contents launcher)
             (goto-char (point-min))
             (if (re-search-forward (rx "HASH=" (group (+ not-newline)) line-end) (point-max) t)
                 (match-string 1)
               'none))))
     ensime--hash)))

;; returns a list of filenames that match the hash of the current buffer
(defun ensime--ctx ()
  (let* ((hash-old ensime--hash)
         (hash-active (ensime--hash t))
         (force-hash (not (equal hash-old hash-active))))
    (when (and hash-active (not (equal 'none hash-active)))
      (remq nil
            (mapcar
             (lambda (buf) (with-current-buffer buf
                             (when (symbol-value 'ensime-mode)
                               (let ((hash (ensime--hash force-hash)))
                                 (when (and hash
                                            (not (equal 'none hash))
                                            (equal hash-active hash)
                                            buffer-file-name
                                            (file-exists-p buffer-file-name))
                                   (when (buffer-modified-p)
                                     (save-buffer))
                                   buffer-file-name)))))
             (remove (current-buffer) (buffer-list)))))))

(defun ensime--launcher ()
  (when (and buffer-file-name
             (not buffer-read-only))
    (let ((launcher (concat "~/.cache/ensime" buffer-file-name)))
      (when (file-exists-p launcher)
        launcher))))

(defun ensime--command-at-point (mode)
  (let ((ctx (ensime--ctx))
        ;; -1 is to adjust for scala's zero based offset
        (p (number-to-string (- (point) 1)))
        (tmp (when (or buffer-read-only (buffer-modified-p))
               (make-temp-file
                "ensime-input"
                nil
                (when buffer-file-name (concat "." (file-name-extension buffer-file-name)))))))
    (when tmp
      ;; make-temp-file with TEXT doesn't support 'silent
      (write-region nil nil tmp nil 'silent))
    (let ((res (ensime--command
                (append (list mode (or tmp buffer-file-name) p) ctx))))
      (when tmp (delete-file tmp))
      res)))

(setq ensime--last-launcher nil)
(defun ensime--command (args)
  (let ((guess "*GUESSING*"))
    (when (member guess mode-line-format)
      (delq guess mode-line-format))
    (when-let ((cmd (or
                     (ensime--launcher)
                     (when ensime--last-launcher
                       (add-to-list 'mode-line-format guess t)
                       ensime--last-launcher)))
               (buf "*ENSIME*"))
      (ignore-errors (kill-buffer buf))
      (let* ((tmp (make-temp-file "ensime"))
             ;; can't redirect stderr to a buffer, only a file
             (res (if (/= 0 (apply 'call-process (append (list cmd nil (list buf tmp) nil) args)))
                      (progn
                        (message "ENSIME failed, see %s for more information" buf)
                        nil)
                    (setq ensime--last-launcher cmd)
                    (with-current-buffer buf
                      (split-string (buffer-substring-no-properties (point-min) (point-max)) "\n" t)))))
        (if (= 0 (file-attribute-size (file-attributes tmp)))
            (ignore-errors (kill-buffer buf))
          (with-current-buffer (get-buffer-create buf)
            (insert-file-contents tmp)
            (read-only-mode 1)))
        (delete-file tmp)
        res))))

(defun ensime--candidate (entry)
  (let ((candidate (if (string-match (rx string-start (group (* (not (any "([")))) (* anychar) ": ") entry)
                       (substring-no-properties entry (match-beginning 1) (match-end 1))
                     entry)))
    (propertize candidate 'meta (substring entry (length candidate)))))

(defconst
  ensime--dir
  (when load-file-name
    (expand-file-name "../" (file-name-directory (file-name-directory load-file-name)))))
(defun ensime--installation ()
  (let ((lib (expand-file-name "~/.cache/ensime/lib"))
        (buf "*ENSIME*"))
    (ignore-errors (kill-buffer buf))

    (unless (executable-find "ng")
      (with-current-buffer (get-buffer-create buf)
        (insert "  wget https://raw.githubusercontent.com/facebook/nailgun/main/nailgun-client/c/ng.c\n")
        (insert "  cc -O2 ng.c -o ng\n")
        (insert "  sudo install ng /usr/local/bin/\n")
        (insert "  rm -f ng.c ng\n\n")))

    (unless (file-directory-p lib)
      (with-current-buffer (get-buffer-create buf)
        (if ensime--dir
            (insert "  cd " ensime--dir "\n  sbt +install\n\n")
          (insert "  sbt +install\n\nfrom the ensime-tng repository.\n\n"))
        (insert "and then reload and recompile your project.\n\n")))

    (when-let ((orig (current-buffer))
               (b (get-buffer buf)))
      (with-current-buffer b
        (goto-char (point-min))
        (insert "ENSIME has not been fully installed:\n\n")
        (goto-char (point-max))
        (insert "(select this window and press \"q\" to close)")
        (read-only-mode 1)
        (local-set-key (kbd "q") #'kill-buffer-and-window))
      (switch-to-buffer-other-window b)
      (pop-to-buffer orig))))

;;;###autoload
(define-minor-mode ensime-mode
  "ENhanced Scala Interaction Mode for Emacs."
  :lighter " ENSIME"
  :keymap (make-sparse-keymap)
  (make-local-variable 'company-backends)
  (add-to-list 'company-backends #'ensime-company)
  (company-mode 1)
  (ensime--installation))

(provide 'ensime-mode)
;;; ensime-mode.el ends here
