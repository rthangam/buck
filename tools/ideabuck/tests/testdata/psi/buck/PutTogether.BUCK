SOME_REFERENCES = [
  'Foo.java',
  'Bar.java',
]

foo_android_library(
  name = 'locale',
  srcs = glob(['*.java'], excludes = SOME_REFERENCES),
  deps = [
    '//java/com/foo/common/android:android',
    '//java/com/foo/debug/log:log',
    '//third-party/java/abc:def',
  ],
  exported_deps = [
    ':which',
  ],
  visibility = [
    'PUBLIC',
  ],
)

android_library(
  name = 'abcdefg',
  srcs = SOME_REFERENCE,
  deps = [
  ],
  visibility = [
    'PUBLIC',
  ],
)

project_config(
  src_target = ':name',
)
