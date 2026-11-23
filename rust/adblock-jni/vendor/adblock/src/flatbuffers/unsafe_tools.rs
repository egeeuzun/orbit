//! Unsafe utility functions for working with flatbuffers and other low-level operations.

use crate::filters::flatbuffer_generated::fb;

// Minimum alignment for the beginning of the flatbuffer data.
const MIN_ALIGNMENT: usize = 8;

/// Converts a flatbuffers Vector to a slice.
/// # Safety
/// This function uses unsafe code to convert flatbuffer vector bytes to a slice.
/// It asserts the vector data is properly aligned and sized.
#[inline(always)]
pub fn fb_vector_to_slice<T>(vector: flatbuffers::Vector<'_, T>) -> &[T] {
    let bytes = vector.bytes();

    const fn static_assert_alignment<T>() {
        // We can't use T with the size more than MIN_ALIGNMENT.
        // Since the beginning of flatbuffer data is aligned to that size,
        // the alignment of the data must be a divisor of MIN_ALIGNMENT.
        assert!(MIN_ALIGNMENT.is_multiple_of(std::mem::size_of::<T>()));
    }
    const { static_assert_alignment::<T>() };

    assert!(bytes.len().is_multiple_of(std::mem::size_of::<T>()));
    assert!((bytes.as_ptr() as usize).is_multiple_of(std::mem::align_of::<T>()));
    unsafe {
        std::slice::from_raw_parts(
            bytes.as_ptr() as *const T,
            bytes.len() / std::mem::size_of::<T>(),
        )
    }
}

// A safe wrapper around the flatbuffer data.
// It could be constructed from raw data (includes the flatbuffer verification)
// or from a builder that have just been used to construct the flatbuffer
// Invariants:
// 1. self.data() is a flatbuffer root safe to read without the verifier. It was checked in
//    `from_raw` or accepted by `Engine::deserialize` after
//    the same-version DAT checksum matched.
// 2. self.data() is aligned to MIN_ALIGNMENT bytes.
//    This is necessary for fb_vector_to_slice.
// ORBIT PATCH: the bytes may live outside the crate (e.g. a read-only
// memory-mapped cache file) instead of an owned copy. See
// `Engine::deserialize_external`. The owner must keep the address of its
// bytes stable for its whole lifetime (true for `Vec`, `Box<[u8]>`, mmaps).
enum Storage {
    Owned(Vec<u8>),
    External(Box<dyn AsRef<[u8]> + Send + Sync>),
}

impl Storage {
    #[inline(always)]
    fn bytes(&self) -> &[u8] {
        match self {
            Storage::Owned(v) => v,
            Storage::External(b) => (**b).as_ref(),
        }
    }
}

pub(crate) struct VerifiedFlatbufferMemory {
    // The buffer containing the flatbuffer data.
    raw_data: Storage,

    // The offset of the data in the buffer.
    // Must be aligned to MIN_ALIGNMENT bytes.
    start: usize,
}

impl VerifiedFlatbufferMemory {
    pub(crate) fn from_raw(data: &[u8]) -> Result<Self, flatbuffers::InvalidFlatbuffer> {
        let memory = Self::from_raw_unchecked(data);

        // Verify that the data is a valid flatbuffer.
        #[cfg(test)]
        root_as_engine_calls::record();
        let _ = fb::root_as_engine(memory.data())?;

        Ok(memory)
    }

    // Creates a new VerifiedFlatbufferMemory from a builder.
    // Skip the verification, the builder must contains a valid FilterList.
    pub(crate) fn from_builder(builder: flatbuffers::FlatBufferBuilder<'_>) -> Self {
        Self::from_raw_unchecked(builder.finished_data())
    }

    // Aligns `data` and wraps it without running the FlatBuffer verifier.
    // The caller must already know `data` satisfies invariant 1.
    pub(crate) fn from_raw_unchecked(data: &[u8]) -> Self {
        let mut vec = Vec::with_capacity(data.len() + MIN_ALIGNMENT);
        let shift = vec.as_ptr() as usize % MIN_ALIGNMENT;

        let start = if shift == 0 {
            0
        } else {
            let shift = vec.as_ptr() as usize % MIN_ALIGNMENT;
            let padding = MIN_ALIGNMENT - shift;
            assert!(vec.capacity() >= padding);
            vec.splice(0..0, vec![0u8; padding]);
            padding
        };

        vec.extend_from_slice(data);
        assert!((vec.as_ptr() as usize + start).is_multiple_of(MIN_ALIGNMENT));

        let memory = Self {
            raw_data: Storage::Owned(vec),
            start,
        };
        assert!((memory.data().as_ptr() as usize).is_multiple_of(MIN_ALIGNMENT));
        memory
    }

    pub(crate) fn root(&self) -> fb::Engine<'_> {
        unsafe { fb::root_as_engine_unchecked(self.data()) }
    }

    pub fn data(&self) -> &[u8] {
        &self.raw_data.bytes()[self.start..]
    }

    // ORBIT PATCH: wraps `owner.as_ref()[start..]` without copying.
    // Gives the owner back if the data is not aligned to MIN_ALIGNMENT, so
    // the caller can fall back to the copying path.
    // The caller must already know the data satisfies invariant 1.
    pub(crate) fn from_external_unchecked(
        owner: Box<dyn AsRef<[u8]> + Send + Sync>,
        start: usize,
    ) -> Result<Self, Box<dyn AsRef<[u8]> + Send + Sync>> {
        let ptr = (*owner).as_ref().as_ptr() as usize + start;
        if start > (*owner).as_ref().len() || !ptr.is_multiple_of(MIN_ALIGNMENT) {
            return Err(owner);
        }
        Ok(Self {
            raw_data: Storage::External(owner),
            start,
        })
    }

    // ORBIT PATCH: as above, but runs the FlatBuffer verifier.
    pub(crate) fn from_external(
        owner: Box<dyn AsRef<[u8]> + Send + Sync>,
        start: usize,
    ) -> Result<Result<Self, Box<dyn AsRef<[u8]> + Send + Sync>>, flatbuffers::InvalidFlatbuffer>
    {
        match Self::from_external_unchecked(owner, start) {
            Ok(memory) => {
                let _ = fb::root_as_engine(memory.data())?;
                Ok(Ok(memory))
            }
            Err(owner) => Ok(Err(owner)),
        }
    }
}

#[cfg(test)]
pub(crate) mod root_as_engine_calls {
    use std::cell::Cell;

    thread_local! {
        static COUNT: Cell<usize> = const { Cell::new(0) };
    }

    pub(super) fn record() {
        COUNT.set(COUNT.get() + 1);
    }

    pub(crate) fn count() -> usize {
        COUNT.get()
    }
}
