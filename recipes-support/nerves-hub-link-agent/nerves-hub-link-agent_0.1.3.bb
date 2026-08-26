SUMMARY = "NervesHub device agent"
DESCRIPTION = "Connects a Linux device to NervesHub over Phoenix Channels, reports \
the running firmware, asks the application on the device whether an update may be \
installed, and applies it through RAUC."
HOMEPAGE = "https://github.com/nerves-hub/nerves-hub-link-agent"
BUGTRACKER = "https://github.com/nerves-hub/nerves-hub-link-agent/issues"

LICENSE = "Apache-2.0"
LIC_FILES_CHKSUM = "file://LICENSE;md5=86d3f3a95c324c9479bd8986968f4327"

SRC_URI = "git://github.com/nerves-hub/nerves-hub-link-agent.git;protocol=https;branch=main \
           file://nerves-hub-link-agent.service \
           file://agent.toml \
           "

# v0.1.1, as a sha.
#
# Not `tag=v${PV}` in SRC_URI: the fetcher treats that as a revision too and
# refuses both at once -- "Conflicting revisions ... found, please specify one
# valid value". The sha is what gets fetched; the tag is what the version in
# the filename means.
#
# Bumping is a new recipe file at the new version rather than an edit to this
# one, so a stale pin shows up in a filename instead of hiding in a variable.
SRCREV = "a17ff231c52b78ced9e63dd1a66fc43c8da86545"

S = "${WORKDIR}/git"

# Where `file://` entries in SRC_URI land. Newer releases unpack them into
# UNPACKDIR; on scarthgap it is undefined, and referring to it there silently
# resolves to nothing -- `install: cannot stat '/agent.toml'`. A weak default
# keeps one path working on both.
UNPACKDIR ??= "${WORKDIR}"

# `cargo_bin`, from meta-rust-bin, rather than poky's `cargo`. The toolchain
# comes from that layer because no released Yocto ships a Rust new enough --
# see the layer README. The class was called `cargo` in older meta-rust-bin and
# collided with poky's; `cargo_bin` is the current name.
inherit cargo_bin cargo-update-recipe-crates systemd useradd

# Yocto fetches offline, so cargo cannot resolve dependencies during
# do_compile. Regenerate with `bitbake -c update_crates nerves-hub-link-agent`
# whenever Cargo.lock changes.
require ${BPN}-crates.inc

# Which cargo features to build, as PACKAGECONFIG.
#
# The crate's default set includes fwup and the sandbox, and a device should
# carry only the tool it has: the features exist so an image that will never
# see a fwup archive does not contain the code to apply one. So the default
# here is the one update tool and nothing else.
#
# There is no feature for the local shell. It is always built, and whether a
# device serves one is decided by the agent's configuration and by NervesHub --
# a device worth opening a shell on is usually one you can no longer reach, so
# putting it behind a rebuild would be putting it behind the problem.
#
# The same goes for health, geo, logging and network_identity: all
# configuration, no features.
PACKAGECONFIG ??= "rauc"

# The fourth field is RDEPENDS: an update tool the agent shells out to has to
# be on the device.
PACKAGECONFIG[rauc] = ",,,rauc"
PACKAGECONFIG[fwup] = ",,,fwup"
PACKAGECONFIG[sandbox] = ""

# PACKAGECONFIG names are cargo feature names, so the list maps straight across.
CARGO_BUILD_FLAGS += "--no-default-features --features ${@','.join(sorted((d.getVar('PACKAGECONFIG') or '').split()))}"

# `rauc install` is a D-Bus client; the work happens in `rauc service`. Without
# it the agent gets "Error creating proxy: Could not connect", which reads like
# a broken bundle rather than a missing daemon. The agent probes for the
# service at startup so that lands while someone is looking at it.
#
# The dependency itself comes from PACKAGECONFIG[rauc] above, so an image built
# for fwup does not drag RAUC in.

SYSTEMD_SERVICE:${PN} = "nerves-hub-link-agent.service"
SYSTEMD_AUTO_ENABLE:${PN} = "enable"

# The agent downloads from the network and runs support scripts, so it does not
# run as root. It still needs group access to whatever it writes -- see
# docs/deploying.md in the source tree.
USERADD_PACKAGES = "${PN}"
GROUPADD_PARAM:${PN} = "--system agent"
# The home directory is the state directory, and it is not created here.
#
# The systemd unit's StateDirectory= makes /var/lib/nerves-hub-link-agent at
# start, owned by this user, so it exists by the time anything needs it. What
# matters is that $HOME names a directory that will be there: a passwd entry
# pointing at a home that was never created is the kind of thing nothing
# notices until something falls back to $HOME and fails against it.
USERADD_PARAM:${PN} = "--system --no-create-home --home-dir ${localstatedir}/lib/nerves-hub-link-agent \
                       --shell /sbin/nologin --gid agent agent"


do_install:append() {
    install -d ${D}${sysconfdir}
    install -m 0644 ${UNPACKDIR}/agent.toml ${D}${sysconfdir}/nerves-hub-link-agent.toml

    # Guarded, because `systemd_system_unitdir` is empty on a distro without
    # systemd and the install then quietly puts the unit nowhere: the package
    # builds, ships a binary with nothing to start it, and says nothing.
    if ${@bb.utils.contains('DISTRO_FEATURES', 'systemd', 'true', 'false', d)}; then
        install -d ${D}${systemd_system_unitdir}
        install -m 0644 ${UNPACKDIR}/nerves-hub-link-agent.service \
            ${D}${systemd_system_unitdir}/nerves-hub-link-agent.service
    fi
}

FILES:${PN} += "${systemd_system_unitdir}/nerves-hub-link-agent.service"

CONFFILES:${PN} = "${sysconfdir}/nerves-hub-link-agent.toml"
