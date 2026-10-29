# Shoalcade RPG Maker compatibility (part of Shoalcade, GPL-3.0). Runs before every RPG Maker game, on the phone and
# in the browser (D147). Windows copies of games sometimes load add-ons the game engine here can't: a Windows-only
# library (Discord status) or a Ruby library the engine leaves out (rubygems). When the real one fails to load, a small
# stand-in takes its place so the game still starts. Nothing changes for an add-on that loads normally.
#
# Games never go online here (Jellyfin is where games and saves come from, and play must work with no network): a
# game's own updater could otherwise download patches into the game folder. Any web request fails the ordinary way
# (an IOError, which game updaters already catch), so the game carries on as if it were offline.

module ShoalcadeCompat
  # Discord status: nothing to connect to here, so every call quietly does nothing.
  def self.discord
    return if defined?(::Discord)
    discord = Module.new
    class << discord
      def connect(*) = false
      def connected? = false
      def disconnect(*) = nil
      def update(*) = nil
      def update_activity(*) = nil
      def method_missing(*) = nil
      def respond_to_missing?(*) = true
    end
    Object.const_set(:Discord, discord)
  end

  # rubygems: games use it for Gem::Version (comparing version numbers like "19.5.3"), so that is what's given.
  def self.rubygems
    Object.const_set(:Gem, Module.new) unless defined?(::Gem)
    return if ::Gem.const_defined?(:Version, false)
    version = Class.new do
      include Comparable
      attr_reader :version
      def self.create(v) = v.is_a?(self) ? v : new(v)
      def self.correct?(v) = !!(v.to_s =~ /\A\s*[0-9]+(\.[0-9a-zA-Z]+)*(-[0-9A-Za-z.-]+)?\s*\z/)
      def initialize(v) = @version = v.to_s.strip
      def to_s = @version
      def segments = @version.scan(/[0-9]+|[a-z]+/i).map { |s| s =~ /\A\d+\z/ ? s.to_i : s }
      def prerelease? = @version =~ /[a-zA-Z]/ ? true : false
      def <=>(other)
        other = self.class.create(other) rescue (return nil)
        a = segments; b = other.segments
        [a.size, b.size].max.times do |i|
          x = a[i] || 0; y = b[i] || 0
          next if x == y
          return -1 if x.is_a?(String) && y.is_a?(Integer) # "1.0.a" < "1.0"
          return 1 if x.is_a?(Integer) && y.is_a?(String)
          return x <=> y
        end
        0
      end
    end
    ::Gem.const_set(:Version, version)
  end

  OFFLINE = "Shoalcade games don't go online"

  # Web requests: every way of opening a connection fails as if there were no network.
  module NoNetwork
    def start(*) = raise(IOError, ShoalcadeCompat::OFFLINE)
    def connect(*) = raise(IOError, ShoalcadeCompat::OFFLINE)
  end

  def self.offline_now
    targets = defined?(::Net::HTTP) ? [::Net::HTTP, ::Net::HTTP.singleton_class] : []
    targets << ::TCPSocket.singleton_class if defined?(::TCPSocket)
    targets.each { |c| c.prepend(NoNetwork) unless c < NoNetwork }
  end

  # Secure web requests need openssl, which the engine leaves out: going online fails the usual way instead.
  def self.openssl = raise(IOError, OFFLINE)

  # Extra buttons some games read (mkxp-z on PC has D, E, F...; this engine has the RPG Maker set). A missing one is
  # simply never pressed.
  EXTRA_BUTTONS = %i[A B C X Y Z L R D E F G H SHIFT CTRL ALT F5 F6 F7 F8 F9 UP DOWN LEFT RIGHT].freeze
  NEVER = {}

  module NeverPressed
    %i[press? trigger? repeat? release? pressex? triggerex? repeatex? releaseex? count time? repeatcount].each do |m|
      define_method(m) do |*args|
        return(m == :count || m == :time? || m == :repeatcount ? 0 : false) if args.size == 1 && ShoalcadeCompat::NEVER.key?(args[0])
        super(*args)
      end
    end
  end

  def self.extra_buttons
    return unless defined?(::Input)
    EXTRA_BUTTONS.each_with_index do |name, i|
      next if ::Input.const_defined?(name, false)
      token = 70_000 + i
      ::Input.const_set(name, token)
      NEVER[token] = name
    end
    ::Input.singleton_class.prepend(NeverPressed) unless NEVER.empty? || ::Input.singleton_class < NeverPressed
  end

  module TopFolder
    %i[each_child children entries foreach].each do |m|
      define_method(m) do |path, *rest, **kw, &blk|
        super(path, *rest, **kw, &blk)
      rescue Errno::ENOENT
        raise unless path.to_s.squeeze('/') == '/'
        blk ? nil : []
      end
    end
  end

  # Calls that mkxp-z on PC has and this engine doesn't. Each is added only if missing, doing the nearest safe thing.
  def self.pc_extras
    if defined?(::System)
      sys = ::System.singleton_class
      sys.define_method(:file_exist?) { |f| ::System.file_exists?(f) } if !sys.method_defined?(:file_exist?) && sys.method_defined?(:file_exists?)
      sys.define_method(:normalize) { |p| p.to_s.tr("\\", '/').squeeze('/') } unless sys.method_defined?(:normalize)
      sys.define_method(:open) { |*| nil } unless sys.method_defined?(:open)
    end
    if defined?(::Graphics)
      gfx = ::Graphics.singleton_class
      # Play time: some games keep their own counters here.
      %i[time_passed start_playing].each { |a| gfx.attr_accessor(a) unless gfx.method_defined?(a) }
      ::Graphics.time_passed = 0 if ::Graphics.respond_to?(:time_passed=) && ::Graphics.time_passed.nil?
      ::Graphics.start_playing ||= Process.clock_gettime(Process::CLOCK_MONOTONIC) if ::Graphics.respond_to?(:start_playing=)
      # Turbo: Shoalcade's own fast-forward does this job.
      %i[toggleTurbo turboIcon toggle].each { |m| gfx.define_method(m) { |*| nil } unless gfx.method_defined?(m) }
      gfx.define_method(:turbo) { false } unless gfx.method_defined?(:turbo)
    end
    # The folder above the save folder: on PC it holds other games' save folders (Rejuvenation looks there for Reborn
    # and other fan games' saves). Here the save folder sits at the top and nothing above it can be listed, so that
    # listing is simply empty instead of an error.
    ::Dir.singleton_class.prepend(TopFolder) unless ::Dir.singleton_class < TopFolder
    # Some games' own engine builds add a helper to animate map tiles; this engine animates them itself.
    unless defined?(::TilemapUtils)
      tu = Module.new
      tu.define_singleton_method(:updateAutoTiles) { |*| nil }
      tu.define_singleton_method(:method_missing) { |*| nil }
      tu.define_singleton_method(:respond_to_missing?) { |*| true }
      Object.const_set(:TilemapUtils, tu)
    end
    if defined?(::Audio)
      aud = ::Audio.singleton_class
      unless aud.method_defined?(:se_play_position)
        aud.define_method(:se_play_position) { |name, volume = 80, pitch = 100, *| ::Audio.se_play(name, volume, pitch) }
      end
      aud.define_method(:bgm_position) { 0 } unless aud.method_defined?(:bgm_position)
      # Music fade-in: newer mkxp-z on PC takes bgm_play(name, volume, pitch, position, fade_in, track); this engine
      # has no fade_in. Such a call plays the music without the fade-in (Rejuvenation checks this as it starts).
      if aud.method_defined?(:bgm_play) && !aud.method_defined?(:__shoalcade_bgm_play)
        aud.alias_method(:__shoalcade_bgm_play, :bgm_play)
        aud.define_method(:bgm_play) do |*args|
          __shoalcade_bgm_play(*args)
        rescue ArgumentError
          raise unless args.size == 6
          __shoalcade_bgm_play(*args[0, 4], args[5])
        end
      end
    end
  end

  # Typing (names and the like). This engine has no text input of its own (Input.gets is always empty), so Shoalcade
  # passes typed text in through a hidden file in the game's save folder: the phone or browser keyboard appends to
  # .shoalcade-typing (a backspace is "\b", Enter "\n", Escape "\e"), and only new text is read each time. When a game turns text input on or
  # off, .shoalcade-keyboard says so ("1"/"0") and the phone or browser shows or hides its keyboard. Dot files are never
  # part of the synced save.
  module Typing
    FILE = '/Save/.shoalcade-typing'
    FLAG = '/Save/.shoalcade-keyboard'
    BACKSPACE_KEYS = [:BACKSPACE, 0x08].freeze
    ENTER_KEYS = [:RETURN, :KP_ENTER, 0x0D].freeze
    ESCAPE_KEYS = [:ESCAPE, 0x1B].freeze
    @pos = 0
    @queue = String.new(encoding: Encoding::UTF_8)

    def self.poll
      data = (File.binread(FILE) rescue nil)
      return if data.nil?
      @pos = 0 if data.bytesize < @pos # started again (a new session)
      return if data.bytesize == @pos
      chunk = data.byteslice(@pos, data.bytesize - @pos).force_encoding(Encoding::UTF_8)
      # Leave a half-written character for next time.
      chunk = chunk.byteslice(0, chunk.bytesize - 1).force_encoding(Encoding::UTF_8) while !chunk.valid_encoding? && chunk.bytesize > 0
      @pos += chunk.bytesize
      @queue << chunk.delete("\r")
    end

    # Typed text up to the next backspace, Enter or Escape (those are then read by next?).
    def self.take
      poll
      i = @queue.index(/[\b\n\e]/)
      i.nil? ? @queue.slice!(0, @queue.length) : @queue.slice!(0, i)
    end

    # True (and used up) if the next thing typed is key: "\b", "\n" or "\e".
    def self.next?(key)
      poll
      return false unless @queue.start_with?(key)
      @queue.slice!(0, 1)
      true
    end

    def self.on? = @on

    def self.keyboard(on)
      @on = on ? true : false
      @asked = false
      File.binwrite(FLAG, on ? '1' : '0')
    rescue StandardError
      nil
    end

    # Some games read typed text (Input.gets) without turning text input on. While a game keeps reading it, that
    # counts as wanting text; a few frames after it stops, the keyboard goes away again.
    @frame = 0
    @read_at = -100

    def self.reading
      @read_at = @frame
      return if @on
      keyboard(true)
      @asked = true
    end

    def self.frame
      @frame += 1
      keyboard(false) if @asked && @frame - @read_at > 20
    end
  end

  module TypingInput
    def gets
      ShoalcadeCompat::Typing.reading
      ((super rescue '') || '') + ShoalcadeCompat::Typing.take
    end

    def text_input=(on)
      ShoalcadeCompat::Typing.keyboard(on)
      (super rescue on)
    end

    # While a game wants text, Enter comes from the typing file (the keyboard's Enter or Done) or the OK button, and
    # Escape from the Back button: this engine only sees a real keyboard's keys, which the phone doesn't send.
    def triggerex?(key)
      t = ShoalcadeCompat::Typing
      return true if t::BACKSPACE_KEYS.include?(key) && t.next?("\b")
      if t.on?
        return true if t::ENTER_KEYS.include?(key) && (t.next?("\n") || trigger?(::Input::C))
        return true if t::ESCAPE_KEYS.include?(key) && (t.next?("\e") || trigger?(::Input::B))
      end
      super
    end
  end

  def self.typing
    return unless defined?(::Input)
    ::Input.singleton_class.prepend(TypingInput) unless ::Input.singleton_class < TypingInput
    Typing.keyboard(false)
  end

  # Games made for the original Windows engine (RGSS, Ruby 1.8): calls that Ruby 1.8 had and this engine's Ruby
  # doesn't. Thread.critical was a "don't switch threads" flag; this engine runs one game thread, so it's a plain flag.
  def self.classic_ruby
    Thread.singleton_class.attr_accessor(:critical) unless Thread.respond_to?(:critical)
    # The Windows temporary folder: games write scratch files there (screenshots, decoded GIF frames). Here it's a
    # hidden folder in the game's save folder; hidden files and folders are never part of the synced save.
    if ENV['TEMP'].nil? || ENV['TEMP'].empty?
      dir = '/Save/.shoalcade-temp'
      Dir.mkdir(dir) unless File.directory?(dir)
      ENV['TEMP'] = ENV['TMP'] = dir
    end
  rescue StandardError
    nil
  end

  # Pokémon Essentials games on the original Windows engine draw a smaller game (DEFAULTSCREENWIDTH x
  # DEFAULTSCREENHEIGHT, usually 512x384) in its fixed 640x480 screen and shrink the Windows window to fit. Here the
  # screen itself is set to the game's size, once, when it first draws.
  class << self
    attr_reader :screen_fitted
  end

  def self.fit_screen
    # The game's settings may load a little after its first frame: keep looking for a few seconds.
    @fit_tries = (@fit_tries || 0) + 1
    @screen_fitted = true if @fit_tries > 300
    return unless Object.const_defined?(:DEFAULTSCREENWIDTH) && Object.const_defined?(:DEFAULTSCREENHEIGHT)
    @screen_fitted = true
    w = ::DEFAULTSCREENWIDTH.to_i
    h = ::DEFAULTSCREENHEIGHT.to_i
    return unless w.between?(160, 1920) && h.between?(120, 1080)
    # Only while the screen is still the engine's starting size (RGSS1 640x480, RGSS2/3 544x416): a game that set its
    # own size (mkxp.json, or its own resize) is left alone.
    size = [ENGINE[:width].call, ENGINE[:height].call]
    return unless [[640, 480], [544, 416]].include?(size) && size != [w, h]
    ENGINE[:resize].call(w, h)
  rescue StandardError
    nil
  end

  ENGINE = {}

  # The engine's own screen calls, kept before a game replaces them with Windows-only versions. Hooked the old way
  # (alias, not prepend) because games alias these calls themselves, and an alias of a prepended method loops.
  def self.keep_engine_calls
    return unless defined?(::Graphics)
    %i[width height resize_screen snap_to_bitmap].each do |m|
      ENGINE[m == :resize_screen ? :resize : m] = ::Graphics.method(m) if ::Graphics.respond_to?(m)
    end
    gfx = ::Graphics.singleton_class
    if ENGINE[:resize] && gfx.method_defined?(:update) && !gfx.method_defined?(:__shoalcade_update)
      gfx.alias_method(:__shoalcade_update, :update)
      gfx.define_method(:update) do |*args|
        ShoalcadeCompat.fit_screen unless ShoalcadeCompat.screen_fitted
        ShoalcadeCompat::Typing.frame
        __shoalcade_update(*args)
      end
    end
    # A common screenshot add-on replaces Graphics.snap_to_bitmap with one that copies the Windows window through GDI
    # (Graphics::BitBlt, Graphics::GetDIBits...). There is no window here, so the engine's own screenshot stays.
    return unless ENGINE[:snap_to_bitmap] && !gfx.method_defined?(:__shoalcade_snap_guard)
    gfx.define_method(:__shoalcade_snap_guard) { true }
    gfx.define_method(:singleton_method_added) do |name|
      super(name) rescue nil
      if name == :snap_to_bitmap && !@__shoalcade_restoring && (const_defined?(:GetDIBits, false) || const_defined?(:BitBlt, false))
        @__shoalcade_restoring = true
        define_singleton_method(:snap_to_bitmap, &ShoalcadeCompat::ENGINE[:snap_to_bitmap])
        @__shoalcade_restoring = false
      end
    end
  end

  # Windows calls (Win32API.new(dll, function, ...)). This engine can't load Windows DLLs, and mkxp-z's own
  # win32_wrap.rb covers only keyboard and mouse state. Here the calls games commonly make get the nearest thing this
  # engine can do: the "window" is the game screen, INI files are read from the game folder, a screenshot is the
  # engine's screenshot, Windows-only extras (consoles, registry, sound devices, network) answer "not available".
  # Keyed on the DLL's file name and the function, so any game using the same DLL gets the same stand-in. An unknown
  # call logs one line and returns 0, as mkxp-z does. No code from any DLL is used: these follow the documented
  # Windows calls, and for game add-ons (KleinBitmap, rubyscreen, gif, rgssdisp) what the game's scripts expect.
  module Win32
    HWND = 42 # the game window (the same number mkxp-z's win32_wrap.rb uses)
    THREAD = 1
    IMPL = {}
    @told = {}

    def self.key(dll, func)
      d = File.basename(dll.to_s.tr('\\', '/')).downcase.sub(/\.(dll|drv|exe)\z/, '')
      "#{d}!#{func.to_s.downcase}"
    end

    # The stand-in for dll!func: exact name first, then without the A/W (ANSI/Unicode) ending.
    def self.find(dll, func)
      k = key(dll, func)
      IMPL[k] || (k =~ /[a-z][aw]\z/ ? IMPL[k.chop] : nil)
    end

    def self.missing(dll, func)
      k = key(dll, func)
      return if @told[k]
      @told[k] = true
      $stderr.puts("[Shoalcade] Win32API not available: #{k}")
    end

    def self.define(dlls, *funcs, &blk)
      Array(dlls).each { |d| funcs.each { |f| IMPL["#{d}!#{f.downcase}"] = blk } }
    end

    # Copies bytes into a caller's buffer (a String), as Windows writes into the memory it is given.
    def self.fill(buf, bytes)
      return unless buf.is_a?(String) && !buf.frozen?
      bytes = bytes.b
      n = [bytes.bytesize, buf.bytesize].min
      n.times { |i| buf.setbyte(i, bytes.getbyte(i)) }
    end

    def self.cstr(v) = v.is_a?(String) ? v.b.split("\0", 2)[0].to_s : nil
    def self.codepage(cp) = cp.to_i == 65_001 ? Encoding::UTF_8 : Encoding::Windows_1252

    def self.width = (ShoalcadeCompat::ENGINE[:width] || ::Graphics.method(:width)).call
    def self.height = (ShoalcadeCompat::ENGINE[:height] || ::Graphics.method(:height)).call

    def self.bitmap(id)
      b = ObjectSpace._id2ref(id.to_i)
      b.is_a?(::Bitmap) && !b.disposed? ? b : nil
    rescue StandardError
      nil
    end

    # INI files (GetPrivateProfileString): read from the game folder. Section and key names ignore case.
    def self.ini_value(file, section, key)
      path = file.to_s.tr('\\', '/').sub(%r{\A\./}, '')
      text = File.binread(path) rescue (return nil)
      cur = nil
      text.each_line do |line|
        line = line.strip
        if line =~ /\A\[(.*)\]\z/
          cur = Regexp.last_match(1).strip.downcase
        elsif cur == section.to_s.downcase && line.include?('=')
          k, v = line.split('=', 2)
          return v.strip if k.strip.downcase == key.to_s.downcase
        end
      end
      nil
    end

    # ----- the window: the game screen is the whole window -----
    define('user32', 'FindWindow', 'FindWindowA') { |cls, title| cstr(cls) == 'RGSS Player' || (cls.is_a?(Integer) && cstr(title)) ? HWND : 0 }
    define('user32', 'FindWindowEx') { |_parent, after, cls, *_| after.to_i == 0 && cstr(cls) == 'RGSS Player' ? HWND : 0 }
    define('user32', 'GetActiveWindow', 'GetForegroundWindow', 'GetFocus', 'GetDesktopWindow') { |*| HWND }
    define('user32', 'GetWindowThreadProcessId') { |_hwnd, pid| fill(pid, [THREAD].pack('V')); THREAD }
    define('kernel32', 'GetCurrentThreadId', 'GetCurrentProcessId') { |*| THREAD }
    define('kernel32', 'GetCurrentProcess') { |*| -1 } # Windows' own "this process" value
    define('user32', 'GetClientRect', 'GetWindowRect') { |_hwnd, rect| fill(rect, [0, 0, width, height].pack('l4')); 1 }
    define('user32', 'GetSystemMetrics') { |i| { 0 => width, 1 => height, 16 => width, 17 => height }.fetch(i.to_i, 0) }
    define('user32', 'ClientToScreen', 'ScreenToClient') { |*| 1 }
    # Moving, sizing, titling or showing the window, and the mouse capture: there's nothing to move here.
    define('user32', 'SetWindowPos', 'MoveWindow', 'ShowWindow', 'UpdateWindow', 'SetForegroundWindow', 'SetFocus',
           'SetWindowText', 'SetWindowTextA', 'SetWindowLong', 'SetWindowLongA', 'SetClassLong', 'SetClassLongA',
           'SetCapture', 'ReleaseCapture', 'RegisterHotKey', 'UnregisterHotKey', 'ClipCursor', 'SetCursorPos') { |*| 1 }
    define('user32', 'GetWindowLong', 'GetWindowLongA', 'GetClassLong', 'GetDC', 'ReleaseDC', 'LoadCursor', 'LoadCursorA',
           'LoadImage', 'LoadImageA', 'SetCursor', 'SendMessage', 'SendMessageA', 'PostMessage', 'PostMessageA',
           'PeekMessage', 'PeekMessageA', 'ToUnicode', 'ToAscii', 'MapVirtualKey', 'MapVirtualKeyA', 'GetKeyboardLayout',
           'keybd_event') { |*| 0 }
    # A Windows message box: written to the log (there's no window to show it in) and answered OK.
    define('user32', 'MessageBox', 'MessageBoxA', 'MessageBoxW') do |_hwnd, text, caption, *_|
      $stderr.puts("[Shoalcade] Message box: #{cstr(caption)}: #{cstr(text)}")
      1
    end

    # ----- kernel32 -----
    define('kernel32', 'GetPrivateProfileString', 'GetPrivateProfileStringA') do |section, key, default, buf, size, file|
      v = ini_value(file, cstr(section), cstr(key)) || cstr(default).to_s
      v = v.b[0, [size.to_i - 1, 0].max]
      fill(buf, v + "\0")
      v.bytesize
    end
    define('kernel32', 'GetPrivateProfileInt', 'GetPrivateProfileIntA') do |section, key, default, file|
      v = ini_value(file, cstr(section), cstr(key))
      v =~ /\A\s*-?\d+/ ? v.to_i : default.to_i
    end
    # Writing INI files or the Windows profile: the game folder is never changed.
    define('kernel32', 'WritePrivateProfileString', 'WritePrivateProfileStringA', 'WriteProfileString', 'WriteProfileStringA') { |*| 1 }
    define('kernel32', 'GetTickCount', 'GetTickCount64', 'timeGetTime') { |*| (Process.clock_gettime(Process::CLOCK_MONOTONIC) * 1000).to_i & 0x7FFFFFFF }
    IMPL['winmm!timegettime'] = IMPL['kernel32!gettickcount']
    define('kernel32', 'QueryPerformanceFrequency') { |buf| fill(buf, [1_000_000].pack('Q<')); 1 }
    define('kernel32', 'QueryPerformanceCounter') { |buf| fill(buf, [(Process.clock_gettime(Process::CLOCK_MONOTONIC) * 1_000_000).to_i].pack('Q<')); 1 }
    define('kernel32', 'GetUserDefaultLangID', 'GetSystemDefaultLangID', 'GetUserDefaultLCID', 'GetSystemDefaultLCID') { |*| 0x0809 } # English (UK)
    define('kernel32', 'GetUserGeoID') { |*| 242 } # United Kingdom
    # Power: reported as mains power with no battery, so games never warn about a low battery.
    define('kernel32', 'GetSystemPowerStatus') { |buf| fill(buf, [1, 128, 255, 0, 0xFFFFFFFF, 0xFFFFFFFF].pack('CCCCVV')); 1 }
    # Copying between two Ruby strings works; anything that reads or writes raw memory addresses can't (returns 0).
    define('kernel32', 'RtlMoveMemory', 'CopyMemory') do |dst, src, len|
      next 0 unless dst.is_a?(String) && src.is_a?(String)
      fill(dst, src.b[0, len.to_i])
      0
    end
    define('kernel32', 'MultiByteToWideChar') do |cp, _flags, src, srclen, dst, dstlen|
      s = cstr(src).to_s
      s = src.b[0, srclen.to_i] if srclen.to_i > 0 && src.is_a?(String)
      w = (s.dup.force_encoding(codepage(cp)).encode(Encoding::UTF_16LE, invalid: :replace, undef: :replace) rescue ''.encode(Encoding::UTF_16LE)).b
      w += "\0\0".b if srclen.to_i < 0
      next w.bytesize / 2 if dstlen.to_i == 0
      fill(dst, w)
      [w.bytesize / 2, dstlen.to_i].min
    end
    define('kernel32', 'WideCharToMultiByte') do |cp, _flags, src, srclen, dst, dstlen, *_|
      next 0 unless src.is_a?(String)
      w = srclen.to_i > 0 ? src.b[0, srclen.to_i * 2] : src.b.scan(/(?:[^\0].|.[^\0])/mn).join
      s = (w.force_encoding(Encoding::UTF_16LE).encode(codepage(cp), invalid: :replace, undef: :replace) rescue '').b
      s += "\0".b if srclen.to_i < 0
      next s.bytesize if dstlen.to_i == 0
      fill(dst, s)
      [s.bytesize, dstlen.to_i].min
    end
    # Process priority, DLL loading, consoles, sleeping: nothing to do here. LoadLibrary answers "couldn't load" (0).
    define('kernel32', 'SetPriorityClass', 'SetThreadPriority', 'FreeLibrary', 'CloseHandle', 'SetConsoleTitle',
           'SetConsoleTitleA', 'SetConsoleActiveScreenBuffer', 'SetConsoleScreenBufferSize', 'OutputDebugString',
           'OutputDebugStringA') { |*| 1 }
    define('kernel32', 'LoadLibrary', 'LoadLibraryA', 'LoadLibraryEx', 'GetModuleHandle', 'GetModuleHandleA', 'GetProcAddress',
           'AllocConsole', 'FreeConsole', 'GetConsoleWindow', 'CreateConsoleScreenBuffer', 'WriteConsole', 'WriteConsoleA',
           'ReadConsole', 'ReadConsoleA', 'GetStdHandle', 'GetLastError', 'FormatMessage', 'FormatMessageA', 'Sleep',
           'Beep') { |*| 0 }

    # ----- other Windows parts: answered "not available", so games take their own fallback -----
    define('advapi32', 'GetUserName', 'GetUserNameA') { |*| 0 } # games then use their own default name
    define('advapi32', 'RegOpenKey', 'RegOpenKeyA', 'RegOpenKeyEx', 'RegOpenKeyExA', 'RegQueryValueEx', 'RegQueryValueExA',
           'RegCreateKey', 'RegCreateKeyA', 'RegCreateKeyEx', 'RegCreateKeyExA', 'RegSetValueEx', 'RegSetValueExA') { |*| 2 } # ERROR_FILE_NOT_FOUND
    define('advapi32', 'RegCloseKey') { |*| 0 }
    define('shell32', 'SHGetFolderPath', 'SHGetFolderPathA', 'SHGetKnownFolderPath', 'SHGetSpecialFolderLocation') { |*| -2_147_467_259 } # E_FAIL
    define('shell32', 'SHGetPathFromIDList', 'SHGetPathFromIDListA', 'ShellExecute', 'ShellExecuteA') { |*| 0 }
    define('ole32', 'CoTaskMemFree', 'CoInitialize', 'CoUninitialize') { |*| 0 }
    define('gdi32', 'AddFontResource', 'AddFontResourceA', 'AddFontResourceEx', 'AddFontResourceExA', 'RemoveFontResource',
           'RemoveFontResourceA') { |*| 1 } # the engine loads the game's Fonts folder itself
    define('gdi32', 'BitBlt', 'StretchBlt', 'GetDIBits', 'SetDIBits', 'CreateCompatibleDC', 'CreateCompatibleBitmap',
           'SelectObject', 'DeleteObject', 'DeleteDC', 'GetPixel', 'SetPixel') { |*| 0 }
    # Sound devices and MCI (recording, CD audio, video): none here. Games then skip the feature.
    define('winmm', 'waveOutGetNumDevs', 'waveInGetNumDevs', 'midiOutGetNumDevs') { |*| 0 }
    define('winmm', 'waveOutOpen', 'waveOutPrepareHeader', 'waveOutUnprepareHeader', 'waveOutWrite', 'waveOutSetVolume',
           'waveOutClose', 'waveOutReset', 'waveInOpen', 'midiOutOpen') { |*| 6 } # MMSYSERR_NODRIVER
    define('winmm', 'mciSendString', 'mciSendStringA') { |*| 275 } # MCIERR_FILE_NOT_FOUND-style failure
    define('winmm', 'mciGetErrorString', 'mciGetErrorStringA') { |_err, buf, *_| fill(buf, "Not available\0"); 1 }
    define('winmm', 'PlaySound', 'PlaySoundA', 'sndPlaySound', 'sndPlaySoundA') { |*| 0 }
    # Network: games never go online here (see the top of this file).
    define(%w[ws2_32 wsock32], 'socket', 'connect', 'bind', 'listen', 'accept', 'send', 'recv', 'sendto', 'recvfrom',
           'select', 'shutdown', 'setsockopt', 'closesocket', 'ioctlsocket') { |*| -1 } # SOCKET_ERROR / INVALID_SOCKET
    define(%w[ws2_32 wsock32], 'WSAStartup', 'WSAGetLastError') { |*| 10_050 } # WSAENETDOWN
    define(%w[ws2_32 wsock32], 'WSACleanup', 'gethostbyname', 'gethostbyaddr', 'getservbyname', 'inet_ntoa') { |*| 0 }
    define(%w[ws2_32 wsock32], 'gethostname') { |*| -1 }
    define(%w[msvcrt msvcr100 msvcr120], '_read', '_write') { |*| -1 } # socket reads and writes through the C library
    define(%w[ws2_32 wsock32], 'inet_addr') { |*| -1 } # INADDR_NONE
    define(%w[ws2_32 wsock32], 'htons', 'ntohs') { |v| [v.to_i].pack('n').unpack1('v') }
    define(%w[ws2_32 wsock32], 'htonl', 'ntohl') { |v| [v.to_i].pack('N').unpack1('V') }

    # ----- fan-game add-ons -----
    # rubyscreen.dll: TakeScreenshot(path) saves the screen to a file; SwapRgb(buffer, size) swaps red and blue.
    define('rubyscreen', 'TakeScreenshot') do |path|
      snap = ShoalcadeCompat::ENGINE[:snap_to_bitmap]
      p = cstr(path).to_s.tr('\\', '/')
      next 0 if snap.nil? || p.empty?
      b = snap.call
      b.to_file(p)
      b.dispose
      1
    end
    define('rubyscreen', 'SwapRgb') do |buf, size|
      next 0 unless buf.is_a?(String)
      n = [size.to_i, buf.bytesize].min / 4
      n.times { |i| r = buf.getbyte(i * 4); buf.setbyte(i * 4, buf.getbyte(i * 4 + 2)); buf.setbyte(i * 4 + 2, r) }
      0
    end
    # rgssdisp.dll: SetResolution(width, height, ...) changes the screen size, which this engine can do itself.
    define('rgssdisp', 'SetResolution', 'SetResolution@12') do |w, h, *_|
      next 0 unless w.to_i.between?(160, 1920) && h.to_i.between?(120, 1080) && ShoalcadeCompat::ENGINE[:resize]
      ShoalcadeCompat::ENGINE[:resize].call(w.to_i, h.to_i)
      1
    end
    # gif.dll turns animated GIFs into PNG frames: answered "couldn't" (0), and the game shows the GIF's first frame,
    # which this engine loads itself.
    define('gif', 'GifToPngFiles', 'GifToPngFilesInMemory', 'CopyDataString', 'FreeDataString') { |*| 0 }
    # KleinBitmap.dll (Klein Bitmap Functions): effects on a bitmap given by its object id, done here pixel by pixel.
    define('kleinbitmap', 'version') { |*| 1 }
    define('kleinbitmap', 'get_kbitmap_width') { |id| (b = bitmap(id)) ? b.width : 0 }
    define('kleinbitmap', 'get_kbitmap_height') { |id| (b = bitmap(id)) ? b.height : 0 }
    { 'scroll_up' => [0, -1], 'scroll_down' => [0, 1], 'scroll_left' => [-1, 0], 'scroll_right' => [1, 0] }.each do |name, (ux, uy)|
      define('kleinbitmap', name) do |id, speed|
        b = bitmap(id) or next 0
        dx = ux * speed.to_i
        dy = uy * speed.to_i
        copy = b.clone
        b.clear
        [[dx, dy], [dx - (dx <=> 0) * b.width, dy - (dy <=> 0) * b.height]].uniq.each { |x, y| b.blt(x, y, copy, copy.rect) }
        copy.dispose
        0
      end
    end
    define('kleinbitmap', 'to_negative') do |id|
      b = bitmap(id) or next 0
      each_pixel(b) { |c| c.set(255 - c.red, 255 - c.green, 255 - c.blue, c.alpha) }
    end
    define('kleinbitmap', 'modify_color') do |id, r1, g1, b1, r2, g2, b2|
      b = bitmap(id) or next 0
      each_pixel(b) { |c| c.set(r2, g2, b2, c.alpha) if c.alpha > 0 && [c.red, c.green, c.blue].map(&:to_i) == [r1, g1, b1].map(&:to_i) }
    end
    define('kleinbitmap', 'to_retro') do |id, *rgb|
      b = bitmap(id) or next 0
      pal = rgb.each_slice(3).first(4)
      next 0 unless pal.size == 4
      each_pixel(b) do |c|
        i = [((c.red * 0.299 + c.green * 0.587 + c.blue * 0.114) / 64).to_i, 3].min
        c.set(*pal[i], c.alpha)
      end
    end
    define('kleinbitmap', 'add_outline') do |id, r, g, bl, px|
      b = bitmap(id) or next 0
      src = b.clone
      col = ::Color.new(r, g, bl)
      n = [px.to_i, 1].max
      b.height.times do |y|
        b.width.times do |x|
          next if src.get_pixel(x, y).alpha > 0
          near = (-n..n).any? { |oy| (-n..n).any? { |ox| (xx = x + ox).between?(0, b.width - 1) && (yy = y + oy).between?(0, b.height - 1) && src.get_pixel(xx, yy).alpha > 0 } }
          b.set_pixel(x, y, col) if near
        end
      end
      src.dispose
      0
    end
    define('kleinbitmap', 'compare_bitmap') do |a, c|
      x = bitmap(a)
      y = bitmap(c)
      next 0 unless x && y && x.width == y.width && x.height == y.height
      x.height.times.all? { |j| x.width.times.all? { |i| x.get_pixel(i, j).to_s == y.get_pixel(i, j).to_s } } ? 1 : 0
    end
    # Its patterns and palette files (.kpal) use the DLL's own formats: not available, the bitmap is left as it is.
    define('kleinbitmap', 'set_pattern', 'save_pallete', 'modify_pallete', 'load_kbitmap', 'save_kbitmap', 'paint_temp_kbitmap') { |*| 0 }

    def self.each_pixel(b)
      b.height.times do |y|
        b.width.times do |x|
          c = b.get_pixel(x, y)
          before = c.to_s
          yield c
          b.set_pixel(x, y, c) unless c.to_s == before
        end
      end
      0
    end

    # In front of Win32API (and mkxp-z's win32_wrap.rb): known calls use the stand-ins above; mkxp-z's own keyboard
    # and mouse stand-ins carry on working; anything else logs once and returns 0 instead of failing.
    module Call
      def initialize(dll, func, *rest)
        @__sc_name = [dll, func]
        @__sc_impl = ShoalcadeCompat::Win32.find(dll, func)
        return if @__sc_impl
        begin
          super
        rescue StandardError, NameError
          nil
        end
        @__sc_wrapped = instance_variable_defined?(:@mkxp_wrap_impl) && !@mkxp_wrap_impl.nil?
      end

      def call(*args)
        return ShoalcadeCompat::Win32.instance_exec(*args, &@__sc_impl) if @__sc_impl
        return super(*args) if @__sc_wrapped
        ShoalcadeCompat::Win32.missing(*@__sc_name)
        0
      end
    end
  end

  def self.win32
    return unless defined?(::Win32API)
    ::Win32API.prepend(Win32::Call) unless ::Win32API < Win32::Call
  end

  STAND_INS = { 'discord' => :discord, 'rubygems' => :rubygems, 'openssl' => :openssl }.freeze

  def self.stand_in(name)
    which = STAND_INS[File.basename(name.to_s, '.*')]
    return false unless which
    send(which)
    true
  end
end

module Kernel
  alias_method :__shoalcade_require, :require
  private :__shoalcade_require

  def require(name)
    loaded = __shoalcade_require(name)
    ShoalcadeCompat.offline_now
    loaded
  rescue LoadError, NotImplementedError
    raise unless ShoalcadeCompat.stand_in(name)
    true
  end
  private :require
end

ShoalcadeCompat.offline_now
ShoalcadeCompat.classic_ruby
ShoalcadeCompat.keep_engine_calls
ShoalcadeCompat.win32
ShoalcadeCompat.extra_buttons
ShoalcadeCompat.pc_extras
ShoalcadeCompat.typing
