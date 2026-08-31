// Readest MultiTTS Paginated Reader Engine
(function () {
    let currentBook = null;
    let currentChapterIndex = 0;
    let totalChapters = 1;
    let currentChapterTitle = '';
    let currentSentenceIndex = -1;
    let sentencesList = [];
    let currentPageIndex = 0;
    let totalPagesInChapter = 1;
    let readingMode = 'paginated'; // 'paginated' or 'scroll'

    // Touch gesture tracking
    let touchStartX = 0;
    let touchStartY = 0;
    let touchStartTime = 0;
    let touchStartSentence = -1;
    let longPressTimer = null;
    let longPressFired = false;

    const LONG_PRESS_MS = 420;
    const TAP_SLOP_PX = 12;
    // A page turn should feel instant; the browser's "smooth" scroll is far too slow
    const PAGE_ANIM_MS = 150;
    const CHAPTER_TURN_COOLDOWN_MS = 900;

    let landOnLastPage = false;
    let lastChapterTurnAt = 0;
    let isAnimating = false;

    function sentenceIndexFromNode(node) {
        while (node && node !== document.body) {
            if (node.classList && node.classList.contains('sentence')) {
                const idx = parseInt(node.dataset.sentenceIndex, 10);
                return isNaN(idx) ? -1 : idx;
            }
            node = node.parentNode;
        }
        return -1;
    }

    function clearLongPress() {
        if (longPressTimer) {
            clearTimeout(longPressTimer);
            longPressTimer = null;
        }
    }

    // The index of the first sentence visible on the current page — used for
    // bookmarks and for resuming where the eye actually is.
    function firstVisibleSentenceIndex() {
        const container = document.getElementById('content-container');
        if (!container) return 0;
        const spans = container.getElementsByClassName('sentence');
        for (let i = 0; i < spans.length; i++) {
            const span = spans[i];
            if (readingMode === 'paginated') {
                const page = Math.floor(span.offsetLeft / window.innerWidth);
                if (page >= currentPageIndex) {
                    const idx = parseInt(span.dataset.sentenceIndex, 10);
                    return isNaN(idx) ? 0 : idx;
                }
            } else {
                const rect = span.getBoundingClientRect();
                if (rect.bottom > 0) {
                    const idx = parseInt(span.dataset.sentenceIndex, 10);
                    return isNaN(idx) ? 0 : idx;
                }
            }
        }
        return 0;
    }

    function reportPosition() {
        if (window.AndroidBridge && window.AndroidBridge.onPageChanged) {
            try {
                window.AndroidBridge.onPageChanged(
                    currentPageIndex,
                    totalPagesInChapter,
                    firstVisibleSentenceIndex()
                );
            } catch (e) { /* bridge not ready */ }
        }
    }

    // Quick eased slide instead of the browser's slow smooth scroll.
    // A new call retargets the animation rather than being dropped, so quick
    // repeated taps keep turning pages instead of feeling stuck.
    let slideToken = 0;
    function slideTo(container, targetLeft) {
        const start = container.scrollLeft;
        const delta = targetLeft - start;
        if (Math.abs(delta) < 1) return;
        const token = ++slideToken;
        isAnimating = true;
        const startedAt = performance.now();
        function step(now) {
            if (token !== slideToken) return; // superseded by a newer page turn
            const t = Math.min(1, (now - startedAt) / PAGE_ANIM_MS);
            const eased = 1 - Math.pow(1 - t, 3);
            container.scrollLeft = start + delta * eased;
            if (t < 1) {
                requestAnimationFrame(step);
            } else {
                container.scrollLeft = targetLeft;
                isAnimating = false;
            }
        }
        requestAnimationFrame(step);
    }

    function chapterTurnAllowed() {
        const now = Date.now();
        if (now - lastChapterTurnAt < CHAPTER_TURN_COOLDOWN_MS) return false;
        lastChapterTurnAt = now;
        return true;
    }

    function showEdgeHint(message) {
        let hint = document.getElementById('edge-hint');
        if (!hint) {
            hint = document.createElement('div');
            hint.id = 'edge-hint';
            document.body.appendChild(hint);
        }
        hint.innerText = message;
        hint.classList.add('visible');
        clearTimeout(hint._timer);
        hint._timer = setTimeout(() => hint.classList.remove('visible'), 1100);
    }

    window.ReaderApp = {
        loadChapterData: function (dataJson) {
            try {
                const data = typeof dataJson === 'string' ? JSON.parse(dataJson) : dataJson;
                currentBook = data.book || {};
                landOnLastPage = data.landOnLastPage === true;
                currentChapterIndex = data.chapterIndex || 0;
                totalChapters = data.totalChapters || 1;
                currentChapterTitle = data.chapterTitle || `Chapter ${currentChapterIndex + 1}`;

                const container = document.getElementById('content-container');
                container.innerHTML = '';
                sentencesList = [];
                currentSentenceIndex = -1;
                let globalSentenceIndex = 0;

                // A way back to the previous chapter, right where the chapter opens
                if (currentChapterIndex > 0) {
                    const prevBtn = document.createElement('div');
                    prevBtn.className = 'chapter-nav top';
                    prevBtn.dataset.navDirection = 'prev';
                    prevBtn.innerText = '← Previous chapter 上一章';
                    container.appendChild(prevBtn);
                }

                // Chapter Header
                const header = document.createElement('div');
                header.className = 'chapter-header';
                const h1 = document.createElement('h1');
                h1.innerText = currentChapterTitle;
                header.appendChild(h1);
                container.appendChild(header);

                const paragraphs = data.paragraphs || [];
                paragraphs.forEach((paraText, pIdx) => {
                    const p = document.createElement('p');
                    p.className = 'paragraph';
                    p.dataset.paragraphIndex = pIdx;

                    const regex = /([^。！？.!?\n\r]+[。！？.!?\n\r]*|\n+)/g;
                    const matches = paraText.match(regex) || [paraText];

                    matches.forEach((sText) => {
                        const trimmed = sText.trim();
                        if (trimmed.length === 0) return;

                        const span = document.createElement('span');
                        span.className = 'sentence';
                        span.dataset.sentenceIndex = globalSentenceIndex;
                        span.id = 'sentence-' + globalSentenceIndex;
                        span.innerText = sText;

                        // No click handler here on purpose: a tap in the page-turn
                        // zones must never start playback at that word. Long-press
                        // (handled in touchend/touchstart) is the deliberate gesture.

                        sentencesList.push({
                            index: globalSentenceIndex,
                            text: trimmed,
                            paragraphIndex: pIdx
                        });

                        globalSentenceIndex++;
                        p.appendChild(span);
                    });

                    container.appendChild(p);
                });

                // ...and on to the next one at the end of the chapter
                if (currentChapterIndex + 1 < totalChapters) {
                    const nextBtn = document.createElement('div');
                    nextBtn.className = 'chapter-nav bottom';
                    nextBtn.dataset.navDirection = 'next';
                    nextBtn.innerText = 'Next chapter 下一章 →';
                    container.appendChild(nextBtn);
                }

                // Reset page to start
                currentPageIndex = 0;
                container.scrollLeft = 0;
                container.scrollTop = 0;

                setTimeout(() => {
                    ReaderApp.recalcPagination();
                    // Entering a chapter backwards should land on its final page,
                    // the way flipping back through a paper book does
                    if (landOnLastPage) {
                        landOnLastPage = false;
                        if (readingMode === 'paginated') {
                            currentPageIndex = Math.max(0, totalPagesInChapter - 1);
                            container.scrollLeft = currentPageIndex * window.innerWidth;
                            ReaderApp.updatePageFooter();
                        } else {
                            // Scrolling mode: arrive at the end of the chapter
                            container.scrollTop = container.scrollHeight;
                        }
                    }
                    reportPosition();
                }, 80);

                if (window.AndroidBridge && window.AndroidBridge.onChapterLoaded) {
                    window.AndroidBridge.onChapterLoaded(
                        currentChapterIndex,
                        currentChapterTitle,
                        JSON.stringify(sentencesList)
                    );
                }
            } catch (err) {
                console.error('Failed to load chapter data', err);
            }
        },

        recalcPagination: function () {
            const container = document.getElementById('content-container');
            if (readingMode === 'paginated') {
                const pageWidth = window.innerWidth;
                const scrollWidth = container.scrollWidth;
                totalPagesInChapter = Math.max(1, Math.ceil(scrollWidth / pageWidth));
                ReaderApp.updatePageFooter();
            } else {
                document.getElementById('footer-chapter-title').innerText = currentChapterTitle;
                document.getElementById('footer-page-info').innerText =
                    `Ch ${currentChapterIndex + 1} / ${totalChapters}`;
            }
        },

        updatePageFooter: function () {
            document.getElementById('footer-chapter-title').innerText = currentChapterTitle;
            document.getElementById('footer-page-info').innerText = `Page ${currentPageIndex + 1} / ${totalPagesInChapter} • Ch ${currentChapterIndex + 1}/${totalChapters}`;
        },

        nextPage: function () {
            const container = document.getElementById('content-container');
            if (readingMode === 'paginated') {
                if (currentPageIndex + 1 < totalPagesInChapter) {
                    currentPageIndex++;
                    slideTo(container, currentPageIndex * window.innerWidth);
                    ReaderApp.updatePageFooter();
                    reportPosition();
                } else if (chapterTurnAllowed()) {
                    // Crossing into the next chapter — say so instead of jumping silently
                    showEdgeHint('Next chapter →');
                    ReaderApp.nextChapter();
                }
            } else {
                container.scrollBy({ top: window.innerHeight * 0.8, behavior: 'smooth' });
                setTimeout(reportPosition, 350);
            }
        },

        prevPage: function () {
            const container = document.getElementById('content-container');
            if (readingMode === 'paginated') {
                if (currentPageIndex > 0) {
                    currentPageIndex--;
                    slideTo(container, currentPageIndex * window.innerWidth);
                    ReaderApp.updatePageFooter();
                    reportPosition();
                } else if (chapterTurnAllowed()) {
                    showEdgeHint('← Previous chapter');
                    ReaderApp.prevChapter();
                }
            } else {
                container.scrollBy({ top: -window.innerHeight * 0.8, behavior: 'smooth' });
                setTimeout(reportPosition, 350);
            }
        },

        nextChapter: function () {
            if (window.AndroidBridge && window.AndroidBridge.requestNextChapter) {
                window.AndroidBridge.requestNextChapter();
            }
        },

        prevChapter: function () {
            if (window.AndroidBridge && window.AndroidBridge.requestPrevChapter) {
                window.AndroidBridge.requestPrevChapter();
            }
        },

        highlightSentence: function (index) {
            if (currentSentenceIndex >= 0) {
                const prev = document.getElementById('sentence-' + currentSentenceIndex);
                if (prev) prev.classList.remove('tts-active');
            }

            currentSentenceIndex = index;
            const current = document.getElementById('sentence-' + index);
            if (current) {
                current.classList.add('tts-active');

                // In paginated mode, calculate which page this sentence belongs to and turn to it
                if (readingMode === 'paginated') {
                    const container = document.getElementById('content-container');
                    const currentOffsetLeft = current.offsetLeft;
                    const targetPage = Math.floor(currentOffsetLeft / window.innerWidth);
                    if (targetPage !== currentPageIndex) {
                        currentPageIndex = targetPage;
                        slideTo(container, currentPageIndex * window.innerWidth);
                        ReaderApp.updatePageFooter();
                        reportPosition();
                    }
                } else {
                    current.scrollIntoView({ behavior: 'smooth', block: 'center' });
                }
            }
        },

        // Move the view to a sentence without starting playback (bookmarks, resume).
        goToSentence: function (index) {
            const target = document.getElementById('sentence-' + index);
            if (!target) return;
            if (readingMode === 'paginated') {
                const container = document.getElementById('content-container');
                currentPageIndex = Math.floor(target.offsetLeft / window.innerWidth);
                container.scrollLeft = currentPageIndex * window.innerWidth;
                ReaderApp.updatePageFooter();
            } else {
                target.scrollIntoView({ behavior: 'auto', block: 'center' });
            }
            target.classList.add('sentence-flash');
            setTimeout(() => target.classList.remove('sentence-flash'), 1400);
            reportPosition();
        },

        onSentenceLongPress: function (index, text) {
            ReaderApp.highlightSentence(index);
            if (window.AndroidBridge && window.AndroidBridge.onSentenceClicked) {
                window.AndroidBridge.onSentenceClicked(index, text);
            }
        },

        setTheme: function (themeName) {
            document.body.className = `${themeName} mode-${readingMode}`;
        },

        setFontSize: function (sizePx) {
            document.documentElement.style.setProperty('--font-size', sizePx + 'px');
            // Keep the reader anchored to the same sentence across a re-flow
            const anchor = currentSentenceIndex >= 0 ? currentSentenceIndex : firstVisibleSentenceIndex();
            setTimeout(() => {
                ReaderApp.recalcPagination();
                ReaderApp.goToSentence(anchor);
            }, 90);
        },

        setLineHeight: function (height) {
            document.documentElement.style.setProperty('--line-height', height);
            setTimeout(() => ReaderApp.recalcPagination(), 80);
        },

        setBottomInset: function (px) {
            document.documentElement.style.setProperty('--bottom-inset', px + 'px');
            setTimeout(() => {
                ReaderApp.recalcPagination();
                if (readingMode === 'paginated') {
                    if (currentPageIndex >= totalPagesInChapter) currentPageIndex = totalPagesInChapter - 1;
                    const container = document.getElementById('content-container');
                    container.scrollTo({ left: currentPageIndex * window.innerWidth });
                    ReaderApp.updatePageFooter();
                }
            }, 80);
        },

        setReadingMode: function (mode) {
            readingMode = mode;
            const currentTheme = document.body.className.split(' ')[0] || 'theme-light';
            document.body.className = `${currentTheme} mode-${readingMode}`;
            const anchor = currentSentenceIndex >= 0 ? currentSentenceIndex : firstVisibleSentenceIndex();
            setTimeout(() => {
                ReaderApp.recalcPagination();
                ReaderApp.goToSentence(anchor);
            }, 90);
        }
    };


    // ---------------------------------------------------------------- words

    const WORD_CHAR = /[\p{L}\p{N}'\u2019\-]/u;
    const CJK = /[\u3400-\u4DBF\u4E00-\u9FFF\uF900-\uFAFF\u3040-\u30FF\uAC00-\uD7AF]/u;

    function clearWordHighlight() {
        document.querySelectorAll('.word-pick').forEach(function (el) {
            const parent = el.parentNode;
            if (!parent) return;
            parent.replaceChild(document.createTextNode(el.textContent), el);
            parent.normalize();
        });
    }

    /**
     * Finds the word under a screen point. CJK has no spaces, so a run of up to
     * eight characters is returned and the dictionary picks the longest prefix
     * it actually knows.
     */
    function wordAtPoint(x, y) {
        let range = null;
        if (document.caretRangeFromPoint) {
            range = document.caretRangeFromPoint(x, y);
        } else if (document.caretPositionFromPoint) {
            const pos = document.caretPositionFromPoint(x, y);
            if (pos) {
                range = document.createRange();
                range.setStart(pos.offsetNode, pos.offset);
            }
        }
        if (!range) return null;

        const node = range.startContainer;
        if (!node || node.nodeType !== 3) return null;
        const text = node.textContent;
        let i = Math.min(range.startOffset, text.length - 1);
        if (i < 0) return null;

        // A tap landing just past a word should still pick that word.
        if (!WORD_CHAR.test(text[i]) && i > 0 && WORD_CHAR.test(text[i - 1])) i--;
        if (!WORD_CHAR.test(text[i])) return null;

        let start = i;
        let end = i + 1;
        if (CJK.test(text[i])) {
            while (end < text.length && CJK.test(text[end]) && end - start < 8) end++;
        } else {
            while (start > 0 && WORD_CHAR.test(text[start - 1]) && !CJK.test(text[start - 1])) start--;
            while (end < text.length && WORD_CHAR.test(text[end]) && !CJK.test(text[end])) end++;
        }

        const word = text.slice(start, end);
        if (!word.trim()) return null;

        // Highlight it, so it is obvious which word the sheet is about.
        clearWordHighlight();
        try {
            const mark = document.createRange();
            mark.setStart(node, start);
            mark.setEnd(node, end);
            const span = document.createElement('span');
            span.className = 'word-pick';
            mark.surroundContents(span);
        } catch (e) {
            // surroundContents throws across element boundaries; the word is
            // still usable, it just does not get highlighted.
        }

        return word;
    }

    ReaderApp.clearWordHighlight = clearWordHighlight;

    // Tap and Swipe Gestures
    document.addEventListener('touchstart', function (e) {
        if (e.touches.length === 1) {
            touchStartX = e.touches[0].clientX;
            touchStartY = e.touches[0].clientY;
            touchStartTime = Date.now();
            touchStartSentence = sentenceIndexFromNode(e.target);
            longPressFired = false;

            clearLongPress();
            if (touchStartSentence >= 0) {
                const pressX = touchStartX;
                const pressY = touchStartY;
                longPressTimer = setTimeout(function () {
                    longPressFired = true;
                    const item = sentencesList[touchStartSentence];
                    if (!item) return;
                    if (navigator.vibrate) navigator.vibrate(18);
                    const word = wordAtPoint(pressX, pressY);
                    if (word && window.AndroidBridge && window.AndroidBridge.onWordLongPress) {
                        window.AndroidBridge.onWordLongPress(word, item.index, item.text);
                    } else {
                        ReaderApp.onSentenceLongPress(item.index, item.text);
                    }
                }, LONG_PRESS_MS);
            }
        }
    }, { passive: true });

    document.addEventListener('touchmove', function (e) {
        if (e.touches.length === 1) {
            const dx = Math.abs(e.touches[0].clientX - touchStartX);
            const dy = Math.abs(e.touches[0].clientY - touchStartY);
            if (dx > TAP_SLOP_PX || dy > TAP_SLOP_PX) clearLongPress();
        }
    }, { passive: true });

    function navButtonFrom(node) {
        while (node && node !== document.body) {
            if (node.classList && node.classList.contains('chapter-nav')) return node;
            node = node.parentNode;
        }
        return null;
    }

    document.addEventListener('touchend', function (e) {
        clearLongPress();
        if (longPressFired) return; // Long-press already started playback here

        // Chapter buttons win over the page-turn zones they happen to sit in
        const navButton = navButtonFrom(e.target);
        if (navButton && chapterTurnAllowed()) {
            if (navButton.dataset.navDirection === 'prev') {
                showEdgeHint('← Previous chapter');
                ReaderApp.prevChapter();
            } else {
                showEdgeHint('Next chapter →');
                ReaderApp.nextChapter();
            }
            return;
        }

        if (e.changedTouches.length === 1) {
            const deltaX = e.changedTouches[0].clientX - touchStartX;
            const deltaY = e.changedTouches[0].clientY - touchStartY;
            const deltaTime = Date.now() - touchStartTime;

            // Swipe Horizontal check
            if (Math.abs(deltaX) > 45 && Math.abs(deltaY) < 80 && deltaTime < 450) {
                if (deltaX < 0) {
                    ReaderApp.nextPage(); // Swipe Left -> Next Page
                } else {
                    ReaderApp.prevPage(); // Swipe Right -> Prev Page
                }
                return;
            }

            // Tap 3-Zone Detection. Zones win over whatever text sits under the
            // finger, so tapping to turn a page never jumps playback to a word.
            if (Math.abs(deltaX) < TAP_SLOP_PX && Math.abs(deltaY) < TAP_SLOP_PX && deltaTime < 400) {
                const screenWidth = window.innerWidth;
                const tapX = touchStartX;

                if (tapX < screenWidth * 0.28) {
                    ReaderApp.prevPage(); // Left 28% -> Prev Page
                } else if (tapX > screenWidth * 0.72) {
                    ReaderApp.nextPage(); // Right 28% -> Next Page
                } else {
                    // Center 44% -> Toggle UI Controls
                    if (window.AndroidBridge && window.AndroidBridge.toggleToolbars) {
                        window.AndroidBridge.toggleToolbars();
                    }
                }
            }
        }
    }, { passive: true });

    document.addEventListener('contextmenu', function (e) {
        // Long-press is our "read from here" gesture; suppress the selection menu.
        e.preventDefault();
    });

    window.addEventListener('resize', () => {
        ReaderApp.recalcPagination();
    });

    document.addEventListener('DOMContentLoaded', () => {
        document.body.className = 'theme-light mode-paginated';
        if (window.AndroidBridge && window.AndroidBridge.onReaderReady) {
            window.AndroidBridge.onReaderReady();
        }
    });
})();
